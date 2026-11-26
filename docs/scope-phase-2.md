# FlashOrder - Scope & Specification: Phase 2
## Distributed Flash Sale Engine, Redis Hot-Path & Event-Driven Reliability

---

## 1. Bối cảnh & Động lực từ Phase 1

Ở Phase 1, hệ thống đã hoàn thiện mô hình **Modular Monolith** với PostgreSQL 16 + Spring JDBC (`JdbcTemplate`) và chứng minh thành công bất biến **Zero Overselling** qua bộ test đa luồng Testcontainers cũng như 24 lượt benchmark `grafana/k6` ([benchmark-phase-1.md](learning/benchmark-phase-1.md)).

Tuy nhiên, kết quả đo lường thực nghiệm ở Phase 1 cũng bộc lộ **4 giới hạn kiến trúc cốt lõi** khi hướng tới mục tiêu Flash Sale `5.000–10.000+ VUs`:

1. **Trần hiệu năng của PostgreSQL đơn lẻ:** Ngay cả với chiến lược tối ưu nhất (`ATOMIC_UPDATE`), throughput chạm trần ở mức **~1,368 RPS** (Baseline) và **~1,563 RPS** (Extreme Contention 1 SKU), với tail latency `p99` lên tới **~2.3s–2.6s** ở tải `1.000 VUs` do tranh chấp single-row lock và chi phí `fsync` WAL trên đĩa.
2. **Thiếu Bulkhead & Admission Control khi dùng Virtual Threads:** Việc cho phép `1.000` Virtual Threads đồng thời tranh giành `20` kết nối HikariCP khiến `p95/p99` tăng gần gấp đôi so với `Platform Threads` (`2,619ms` vs `1,018ms`) và làm nổ **`4,644` lỗi HikariCP timeout** với `PESSIMISTIC`.
3. **Rủi ro Dual-Write & Đồng bộ cứng giữa các Domain:** Trong `OrderService.createOrder`, việc trừ kho (`inventory`) và tạo đơn (`order`) chưa nằm trong một cơ chế đảm bảo nguyên tử xuyên ranh giới; nếu lưu đơn thất bại sau khi giữ kho, bản ghi giữ chỗ bị treo cho tới khi hết TTL.
4. **Thiếu cơ chế Bù trừ Hoàn tiền thực sự (Compensating Transaction):** Trong `OrderService.payOrder`, khi thanh toán thành công nhưng worker vừa thu hồi reservation hết hạn, hệ thống mới chỉ dừng ở mức ghi log cảnh báo (`Compensating refund required`) thay vì kích hoạt một quy trình hoàn trả nhất quán.

---

## 2. Mục tiêu Giai đoạn 2 (Phase 2 Objectives)

Phase 2 chuyển dịch hệ thống từ **Modular Monolith đồng bộ** sang **Kiến trúc Phân tán Hướng sự kiện (Event-Driven Distributed Architecture)** nhằm giải quyết đồng thời 2 bài toán: **High-Throughput Edge Admission** và **Distributed Data Reliability**.

- **Mục tiêu kỹ thuật cốt lõi:**
  1. **Tách dịch vụ theo ranh giới Domain (Database-per-Service):** Kế thừa ranh giới của [ADR-0001](adr/0001-modular-monolith-architecture.md) để tách thành các service độc lập (`order-service`, `inventory-service`, `payment-service`), mỗi service sở hữu schema/database PostgreSQL riêng biệt, tuyệt đối không truy vấn chéo DB.
  2. **Đưa Hot-Path lên Redis Atomic Lua Script:** Giảm tải cho PostgreSQL bằng tầng giữ chỗ nhanh trên bộ nhớ Redis (`sub-20ms` ở edge), xử lý từ chối hết hàng (`409 Conflict`) và giới hạn mua mỗi user ngay tại Redis mà không chạm DB.
  3. **Xử lý Bất đồng bộ Tin cậy với Kafka & Transactional Outbox Pattern:** Tiếp nhận đơn hàng nhanh (`202 Accepted`), đồng bộ trạng thái xuyên service qua Apache Kafka kết hợp **Outbox Pattern** (Producer) và **Inbox/Idempotent Consumer** (Consumer) để đảm bảo **At-Least-Once Delivery** và **Zero Lost Orders**.
  4. **Saga Pattern & Compensating Transactions:** Hoàn chỉnh vòng đời đặt hàng – giữ kho – thanh toán – hoàn kho/hoàn tiền phân tán, xử lý triệt để các kịch bản lỗi từng phần (partial failure) và tranh chấp TTL.
  5. **Traffic Shaping & Bulkhead cho Virtual Threads:** Áp dụng Distributed Rate Limiting (Redis), Semaphore Bulkhead bảo vệ HikariCP/DB trước Virtual Threads, và cơ chế Backpressure cho Kafka Consumers.
  6. **Distributed Observability & Chaos Testing:** Tích hợp Distributed Tracing (`traceId` xuyên HTTP + Kafka), Metrics (Prometheus), bộ kiểm thử lỗi phân tán (Chaos/Failure Injection với Testcontainers), và Benchmark Phase 2 so sánh trực diện với số liệu Phase 1.

---

## 3. Kiến trúc Tổng thể & Phân ranh Services (Target Architecture)

### 3.1. Sơ đồ Kiến trúc Mục tiêu

```text
                      ┌─────────────────────────────────────────┐
                      │         Client / k6 Load Generator      │
                      └────────────────────┬────────────────────┘
                                           │ HTTP + Idempotency-Key
                                           ▼
┌───────────────────────────────────────────────────────────────────────────────────┐
│                                   order-service                                   │
│  ┌──────────────────┐   ┌────────────────────┐   ┌─────────────────────────────┐  │
│  │ Rate Limiter &   │──▶│ Redis Hot-Path Gate│──▶│ Order API (202 Accepted)    │  │
│  │ Idempotency      │   │ (Lua: Stock + Cap) │   │ + Saga Orchestrator / State │  │
│  └──────────────────┘   └─────────┬──────────┘   └──────────────┬──────────────┘  │
│                                   │                             │                 │
│                                   │              ┌──────────────▼──────────────┐  │
│                                   │              │ PostgreSQL (order_db)       │  │
│                                   │              │ orders + outbox_events      │  │
│                                   │              └──────────────┬──────────────┘  │
└───────────────────────────────────┼─────────────────────────────┼─────────────────┘
                                    │                             │ Outbox Relay
                                    ▼                             ▼
                           ┌─────────────────┐         ┌──────────────────────┐
                           │     Redis 7     │         │  Apache Kafka (KRaft)│
                           │ - Stock Counter │         │ - order.created      │
                           │ - User Quota    │         │ - stock.reserved     │
                           │ - Resv TTL ZSET │         │ - payment.requested  │
                           │ - Idempotency   │         │ - payment.completed  │
                           └────────▲────────┘         │ - stock.released/DLT │
                                    │                  └──────────┬───────────┘
         Reconciliation /           │                             │
         Redis Stock Restore        │                             ▼
┌───────────────────────────────────┼─────────────────────────────┼─────────────────┐
│                             inventory-service                   │                 │
│  ┌────────────────────────────────┴──────────────────────┐   ┌──▼──────────────┐  │
│  │ Bulkhead + ATOMIC_UPDATE + TTL Sweeper + Reconciler   │◀──│ Inbox Consumer  │  │
│  └───────────────────────────┬───────────────────────────┘   │ (Deduplication) │  │
│                              ▼                               └─────────────────┘  │
│                  ┌───────────────────────────────────────┐                        │
│                  │ PostgreSQL (inventory_db)             │                        │
│                  │ inventory_items + stock_reservations  │                        │
│                  │ + processed_events + outbox_events    │                        │
│                  └───────────────────────────────────────┘                        │
└───────────────────────────────────────────────────────────────────────────────────┘
                                    ▲
                                    │ Kafka Events (payment.requested / result)
┌───────────────────────────────────┴───────────────────────────────────────────────┐
│                                 payment-service                                   │
│  - Mô phỏng độ trễ I/O thực tế (Virtual Threads phát huy thế mạnh)               │
│  - Mô phỏng Failure Rate & Compensating Refund Execution                          │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2. Cấu trúc Multi-Module Đề xuất

Kế thừa hạ tầng Gradle, `Spotless`, `Taskfile.yml` và `benchmarks/`, tổ chức codebase theo hướng Multi-Module Gradle để dùng chung contracts/events rõ ràng nhưng đóng gói và chạy độc lập từng service:

```text
.
├── platform/
│   ├── common-events/          # Event Contracts, Envelope (eventId, traceId, occurredAt)
│   └── common-observability/   # Tracing, Metrics, Bulkhead/Idempotency abstractions
├── services/
│   ├── order-service/          # Port 8080: Edge API, RateLimit, Redis Gate, Order DB, Saga
│   ├── inventory-service/      # Port 8081: Inventory DB, Atomic SQL, Inbox/Outbox, TTL & Reconciler
│   └── payment-service/        # Port 8082: Async Payment Worker & Refund Compensation
├── benchmarks/                 # Kịch bản k6 Phase 2 mở rộng (5k-10k VUs + E2E lag)
├── docs/
│   ├── adr/                    # Bổ sung ADR-0003..0006 cho các quyết định Phase 2
│   └── learning/               # Báo cáo Benchmark Phase 2 & Phân tích Saga/Outbox
├── docker/postgres/            # Init script tạo 3 logical DBs & 3 users phân quyền riêng
├── docker-compose.yml          # Postgres (3 isolated DBs), Redis 7, Kafka (KRaft) + profile observability
└── docker-compose.bench.yml    # Môi trường benchmark phân tán cô lập
```

> **Quy tắc ranh giới bất khả xâm phạm:**
> - **Mốc Phase 1** được bảo toàn tại Git tag `phase-1` để làm baseline đối chiếu.
> - Trong Docker local/benchmark, dùng **1 container PostgreSQL duy nhất nhưng tách thành 3 logical databases và 3 DB users phân quyền cô lập** (`order_db`, `inventory_db`, `payment_db`) nhằm tiết kiệm RAM trên máy 16GB mà vẫn đảm bảo 100% nguyên tắc Database-per-Service (không service nào có quyền truy cập schema/bảng của service khác).
> - Không dùng `Hibernate/JPA` cho các luồng hotspot & outbox/inbox; tiếp tục dùng `Spring JDBC / JdbcTemplate` theo [ADR-0002](adr/0002-spring-jdbc-over-jpa-for-concurrency-control.md) để kiểm soát tuyệt đối `FOR UPDATE SKIP LOCKED`, atomic update và lock duration.
> - **Observability phân tầng:** `Micrometer Tracing` (truyền `traceparent` xuyên HTTP $\rightarrow$ `outbox_events` $\rightarrow$ Kafka Headers) và `Prometheus` metrics bật mặc định trong app; visualization stack (`Prometheus`, `Grafana`, `Zipkin`) đặt dưới Docker Compose `profiles: ["observability"]` để chạy on-demand, tránh gây nhiễu tài nguyên khi benchmark k6.

---

## 4. Thiết kế Luồng Nghiệp vụ Cốt lõi (Core Flows)

### 4.1. Luồng Đặt hàng Flash Sale (Hot-Path: Redis + Async Persistence)

Thay vì để `10.000` requests xếp hàng trước HikariCP như Phase 1, luồng `POST /api/v2/orders` được tách thành **2 pha (Admission vs Durable Persistence)**:

1. **Edge Admission & Protection (`order-service`):**
   - **Rate Limiting:** Kiểm tra giới hạn request theo `userId` / IP bằng Redis Sliding Window. Nếu vượt ngưỡng -> trả `429 Too Many Requests`.
   - **Idempotency Guard:** Kiểm tra header `Idempotency-Key` trong Redis.
     - Nếu key đã hoàn tất -> trả lại response đã cache (`202` / `409`) mà không trừ lại kho.
     - Nếu key đang xử lý (`IN_PROGRESS`) -> trả `409 Conflict` hoặc `425/409` chống double-click song song.
2. **Redis Atomic Reservation (1 Round-Trip qua Lua Script):**
   - Script thực thi nguyên tử trên Redis:
     1. Kiểm tra giới hạn mua tối đa của user trên SKU (`inv:user_qty:{skuId}:{userId}`).
     2. Kiểm tra tồn kho nóng `inv:stock:{skuId} >= quantity`.
     3. Nếu không đủ -> trả mã `OUT_OF_STOCK` -> API trả `409 Conflict` ngay lập tức (**không chạm Kafka, không chạm PostgreSQL**).
     4. Nếu đủ -> `DECRBY inv:stock:{skuId} quantity`, tăng quota user, ghi metadata `inv:res:{orderId}` và đưa vào ZSET `inv:res:expiry` với `score = expiresAtEpochMs`.
3. **Durable Acceptance (Order DB + Outbox):**
   - Trong cùng 1 local transaction trên `order_db`:
     - `INSERT INTO orders` với trạng thái `PENDING_CONFIRMATION` (hoặc `RESERVED`).
     - `INSERT INTO outbox_events` với event `OrderReservationRequested` (mang `orderId`, `skuId`, `quantity`, `expiresAt`, `traceId`).
   - *Fallback an toàn:* Nếu bước ghi `order_db` thất bại, thực hiện hoàn trả ngay slot trên Redis (và dựa vào `inv:res:expiry` như tuyến phòng thủ thứ 2 nếu app bị crash giữa chừng).
   - Trả về HTTP `202 Accepted` kèm `orderId` và polling URI `/api/v2/orders/{id}`.
4. **Async Inventory Persistence (`inventory-service`):**
   - Outbox Relay đẩy event từ `order_db.outbox_events` lên Kafka topic `flashorder.order.events` (partition key = `skuId` hoặc `orderId` tùy thiết kế ADR).
   - `inventory-service` tiêu thụ event qua **Bulkhead** (giới hạn số worker ghi DB đồng thời phù hợp với HikariCP pool):
     - Kiểm tra bảng `processed_events` (Inbox Pattern) để bỏ qua nếu event đã xử lý.
     - Thực thi `ATOMIC_UPDATE` trên `inventory_db.inventory_items` và tạo `stock_reservations` (`RESERVED`).
     - Ghi `StockReserved` (hoặc `StockReservationFailed` nếu kho nguồn thực tế cạn/drift) vào `inventory_db.outbox_events`.

### 4.2. Luồng Thanh toán & Saga Compensation (`Pay` / `Expire` / `Cancel`)

1. **Thanh toán (`POST /api/v2/orders/{id}/pay` hoặc async command):**
   - `order-service` chuyển trạng thái đơn sang `PAYMENT_PENDING`, phát sự kiện/lệnh sang `payment-service` (hỗ trợ cấu hình độ trễ mạng/I/O giả lập `50–200ms` để kiểm chứng hiệu quả thực sự của **Java 25 Virtual Threads**).
2. **Trường hợp Thanh toán Thành công (`PaymentSucceeded`):**
   - `inventory-service` thực hiện xác nhận xuất kho nguyên tử (`RESERVED -> CONFIRMED`, giảm `total_stock` và `reserved_stock`), xóa `inv:res:{orderId}` khỏi Redis expiry ZSET.
   - `order-service` cập nhật đơn hàng thành `COMPLETED`.
3. **Trường hợp Tranh chấp với TTL Expiration (Giải quyết triệt để lỗ hổng Phase 1):**
   - Nếu `inventory-service` đã thu hồi reservation (`EXPIRED` / `RELEASED`) trước khi nhận được `PaymentSucceeded`:
     - `inventory-service` từ chối confirm và phát sự kiện `StockConfirmRejected (REASON: RESERVATION_EXPIRED)` qua Outbox.
     - **Saga Compensation:** `order-service` chuyển đơn sang `CANCELLED_REFUNDED` (hoặc `REFUNDING -> CANCELLED`) và phát sự kiện `PaymentRefundRequested` tới `payment-service` để hoàn tiền idempotent.
4. **Trường hợp Thanh toán Thất bại hoặc Hết hạn TTL:**
   - `inventory-service` giải phóng `reserved_stock` trong PostgreSQL **và** phát sự kiện/thực thi hoàn lại đúng `quantity` vào `inv:stock:{skuId}` trên Redis (kèm cờ idempotent để không bao giờ cộng đúp 2 lần cho cùng 1 `orderId`!).

---

## 5. Mô hình Dữ liệu & Business Invariants Phân tán

### 5.1. 6 Bất biến Phân tán (Distributed System Invariants)

1. **Global Zero Overselling Invariant:**
   - Tại PostgreSQL (Source of Truth): `0 <= reserved_stock <= total_stock` luôn đúng ở mọi commit.
   - Tại Redis (Hot-Path Gate): Giá trị khởi tạo `inv:stock:{skuId}` $\le$ `available_stock` trong DB; mọi thao tác hoàn kho Redis phải gắn với 1 `orderId` đã trừ thành công trước đó (chống double-refund làm phình tồn kho ảo trên Redis!).
2. **Exactly-Once State Effect (Idempotent Processing):**
   - Một request có cùng `Idempotency-Key` hoặc một Kafka message bị redeliver $N$ lần chỉ được phép tạo tối đa 1 đơn hàng, trừ kho 1 lần, và hoàn kho 1 lần.
3. **No Dual-Write Gap (Outbox Guarantee):**
   - Không bao giờ cập nhật DB và gửi Kafka trực tiếp độc lập mà không có Transactional Outbox. Mọi chuyển đổi trạng thái phải cùng nằm trong 1 ACID transaction với `outbox_events`.
4. **No Orphaned Reservation (Dual-Layer TTL Recovery):**
   - Nếu `order-service` chết ngay sau khi trừ Redis trước khi kịp ghi DB/Kafka, Redis expiry sweeper sẽ phát hiện `inv:res:{orderId}` quá hạn (chưa được confirm bởi Postgres) và tự động thu hồi slot về `inv:stock:{skuId}`.
5. **Monotonic Order State Machine:**
   - Trạng thái đơn hàng chỉ đi theo chiều hợp lệ (ví dụ không thể chuyển từ `CANCELLED` về `COMPLETED` nếu message đến sai thứ tự). Mọi event đến muộn phải bị từ chối hoặc kích hoạt compensation.
6. **Bounded Drift & Self-Healing (Reconciliation):**
   - Hệ thống chấp nhận độ trễ nhất quán cuối cùng (Eventual Consistency) tính bằng mili-giây/giây giữa Redis và PostgreSQL, nhưng phải có Reconciliation Worker phát hiện sai lệch giữa `redis_stock` và `pg_available_stock - pending_in_flight` và tự động hiệu chỉnh/cảnh báo.

### 5.2. Thiết kế Schema Dữ liệu Bổ sung (Conceptual SQL)

#### A. `order_db` (Sở hữu bởi `order-service`)
- **`orders`**:
  - Mở rộng từ Phase 1: thêm `idempotency_key VARCHAR(128) UNIQUE`, `sku_id VARCHAR(64)`, `quantity INT`, `version BIGINT NOT NULL DEFAULT 0`, `failure_reason VARCHAR(255)`, `updated_at TIMESTAMPTZ`.
  - Trạng thái mở rộng: `PENDING_RESERVATION`, `RESERVED`, `PAYMENT_PENDING`, `COMPLETED`, `CANCELLED`, `REFUND_REQUIRED`, `REFUNDED`, `FAILED`.
- **`outbox_events`**:
  - `id UUID PRIMARY KEY`, `aggregate_type VARCHAR(64)`, `aggregate_id VARCHAR(64)`, `event_type VARCHAR(64)`, `payload JSONB NOT NULL`, `trace_id VARCHAR(64)`, `status VARCHAR(16)` (`PENDING`, `PUBLISHED`, `FAILED`), `retry_count INT DEFAULT 0`, `created_at TIMESTAMPTZ`, `published_at TIMESTAMPTZ`.
  - Index trên `(status, created_at)` hỗ trợ `FOR UPDATE SKIP LOCKED`.
- **`idempotency_keys`**:
  - `key VARCHAR(128) PRIMARY KEY`, `request_hash VARCHAR(64)`, `status VARCHAR(16)` (`IN_PROGRESS`, `COMPLETED`), `response_status INT`, `response_body JSONB`, `expires_at TIMESTAMPTZ`.

#### B. `inventory_db` (Sở hữu bởi `inventory-service`)
- **`inventory_items`**: Kế thừa Phase 1 (`CHECK (reserved_stock <= total_stock)`).
- **`stock_reservations`**: Kế thừa Phase 1 + thêm `redis_restored BOOLEAN NOT NULL DEFAULT FALSE` (đánh dấu đã hoàn kho lên Redis chưa để đảm bảo idempotent restore).
- **`processed_events` (Inbox Table)**:
  - `event_id UUID PRIMARY KEY`, `consumer_group VARCHAR(64)`, `event_type VARCHAR(64)`, `processed_at TIMESTAMPTZ`.
- **`outbox_events`**: Tương tự `order_db.outbox_events` để phát `StockReserved`, `StockReservationFailed`, `StockConfirmed`, `StockConfirmRejected`, `StockReleased`.

#### C. `payment_db` (Sở hữu bởi `payment-service`)
- **`payment_transactions`**:
  - `id UUID PRIMARY KEY`, `order_id UUID NOT NULL UNIQUE`, `amount NUMERIC(12, 2) NOT NULL`, `status VARCHAR(32)` (`SUCCEEDED`, `FAILED`, `REFUNDED`), `simulated_latency_ms INT`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`.
- **`processed_events` (Inbox Table)** & **`outbox_events`**: Đảm bảo xử lý `PaymentRequested` và `PaymentRefundRequested` đúng 1 lần (idempotent charge/refund) và phát sự kiện kết quả đáng tin cậy.

### 5.3. Thiết kế Cấu trúc Redis Keys (Conceptual)

| Key Pattern | Kiểu dữ liệu | Mục đích & TTL |
| :--- | :--- | :--- |
| `inv:stock:{skuId}` | `String (Int)` | Số lượng tồn kho nóng khả dụng cho Flash Sale. |
| `inv:user_qty:{skuId}:{userId}` | `String (Int)` | Đếm số lượng user đã giữ chỗ trên SKU (chống gom hàng), TTL theo đợt Flash Sale. |
| `inv:res:{orderId}` | `Hash` | Lưu `{skuId, userId, quantity, state}` phục vụ kiểm tra trạng thái & hoàn trả khi quá hạn. |
| `inv:res:expiry` | `ZSET` | Score = `expires_at_epoch_ms`, Member = `orderId`. Dùng để quét các slot bị rớt trước khi vào DB. |
| `idem:{key}` | `String/Hash` | Cache ngắn hạn trạng thái & response cho `Idempotency-Key` (TTL ví dụ 15–30 phút). |
| `rl:user:{userId}` | `ZSET / Bucket` | Sliding Window / Token Bucket Rate Limiter. |

---

## 6. Các Quyết định Kiến trúc Cần Phân tích Trade-off (ADR Roadmap)

Trong quá trình chuyển sang Design & Plan cho Phase 2, mỗi quyết định trọng yếu sẽ được phản biện ít nhất 2 phương án và ghi nhận thành ADR dưới `docs/adr/`:

1. **ADR-0003: Cơ chế Giữ chỗ Hot-Path (Redis Atomic Lua Script vs Pure PostgreSQL vs Distributed Lock / Redlock)**
   - Phân tích lý do chọn Lua Script single-node/master trên Redis thay vì Redlock hay giữ nguyên DB.
   - Đánh giá rủi ro Redis mất dữ liệu khi crash (AOF `everysec` vs `always`) và phương án warm-up/reconcile từ PostgreSQL.
2. **ADR-0004: Đảm bảo Tính Bền vững Sự kiện (Transactional Outbox Polling `SKIP LOCKED` vs Debezium CDC vs Direct Kafka Publish)**
   - Phân tích chi phí vận hành, độ trễ (latency) và cơ chế ordering.
3. **ADR-0005: Kiến trúc Saga & Xử lý Hoàn tiền Bù trừ (Saga Orchestration tại `order-service` vs Choreography thuần túy)**
   - Phân tích điểm mạnh/yếu khi quản lý state machine phức tạp (`Reservation` + `Payment` + `TTL` + `Refund`).
4. **ADR-0006: Kiểm soát Concurrency cho Virtual Threads (Semaphore Bulkhead & Kafka Concurrency Tuning vs Unbounded Virtual Threads)**
   - Chứng minh cách khắc phục triệt để lỗi `HikariCP connection-timeout` đã phát hiện trong [benchmark-phase-1.md](learning/benchmark-phase-1.md).

---

## 7. Kế hoạch Kiểm thử Phân tán & Failure Injection (Test Strategy)

Khác với Phase 1 chỉ tập trung vào Thread Contention trong 1 JVM, Phase 2 mở rộng ma trận kiểm thử sang **Distributed Reliability & Chaos Testing** bằng Testcontainers (PostgreSQL x2 + Redis + Kafka):

1. **Lua Script & Edge Concurrency Tests:**
   - 500–1.000 concurrent threads cùng gọi Redis Lua gate cho `stock = 100` -> Đúng 100 slot được cấp, `inv:stock` về 0, không bao giờ âm.
   - Kiểm tra `Idempotency-Key` khi 50 requests trùng key bắn vào cùng 1 mili-giây.
2. **At-Least-Once & Idempotency Tests (Inbox/Outbox):**
   - Cố tình đẩy cùng một Kafka event `OrderReservationRequested` **5 lần liên tiếp** -> `inventory_items.reserved_stock` chỉ tăng đúng 1 lần.
   - Đẩy event sai thứ tự (`PaymentSucceeded` hoặc `Release` trước `Reserve`) -> Xác minh state machine không bị corrupt.
3. **Failure Injection / Chaos Scenarios:**
   - **Broker Outage:** Tạm dừng Kafka container trong lúc `order-service` nhận đơn -> Outbox lưu sự kiện `PENDING`; khi Kafka khởi động lại, toàn bộ đơn được đồng bộ bù sang `inventory-service` không mất đơn nào.
   - **Crash giữa Redis và Order DB:** Giả lập exception ngay sau khi Redis Lua trừ kho thành công nhưng DB rollback -> Xác minh cơ chế rollback tức thời và cơ chế TTL sweep dự phòng khôi phục lại đúng tồn kho Redis.
   - **Payment vs TTL Race Condition:** Thanh toán hoàn tất ngay sau khi TTL vừa hết hạn -> Xác minh Saga tự động kích hoạt `PaymentRefundRequested` và trạng thái cuối cùng nhất quán (`CANCELLED_REFUNDED` + `RELEASED`).
   - **Poison Pill Message:** Event hỏng định dạng vào Kafka -> Retry có giới hạn với backoff rồi chuyển vào Dead Letter Topic (`*.DLT`), không làm tắc consumer partition.

---

## 8. Kế hoạch Benchmark Phase 2 (k6 & So sánh với Phase 1)

Bộ benchmark Phase 2 dùng lại môi trường chuẩn từ Phase 1 để so sánh công bằng (Apples-to-Apples) và mở rộng thêm bài test tải thực sự của Flash Sale:

| Scenario | Tải mô phỏng | Mục tiêu Đo lường | So sánh tham chiếu Phase 1 |
| :--- | :--- | :--- | :--- |
| **P2-01. Hotspot High (Parity)** | 1.000 VUs, 100 SKU, 30s | Edge RPS, Edge p95/p99 (`202 Accepted`), Outbox -> Inventory DB lag | Phase 1 (`ATOMIC_UPDATE`): `1,323 RPS`, `p95 = 1,786ms` |
| **P2-02. Extreme Contention (Parity)** | 1.000 VUs, 1 SKU (500 stock), 10s | Tốc độ xả tải `409 Conflict` tại Redis, `500/500` đơn DB hợp lệ | Phase 1 (`ATOMIC_UPDATE`): `1,563 RPS`, `p99 = 2,304ms` |
| **P2-03. Flash Sale Scale-Out** | 5.000–10.000 VUs, 10 SKU, 30s | Kiểm chứng mục tiêu `< 50ms` p95 ở Edge Gate, Rate Limiter hiệu quả | Phase 1 chưa chịu được tải này không qua bulkhead |
| **P2-04. Slow Payment I/O** | 1.000 VUs, Payment delay `150ms` | So sánh `Virtual Threads` + `Bulkhead` vs `Platform Threads` khi có I/O ngoài DB | Kiểm chứng giả thuyết ở mục 4.2 Báo cáo Phase 1 |
| **P2-05. Burst & Backpressure** | 0 $\rightarrow$ 5.000 VUs spike, 60s | Quan sát Kafka Consumer Lag dâng lên rồi tự rút về 0 không rớt HikariCP | Không có ở Phase 1 |

**Bắt buộc kiểm chứng hậu benchmark (`bench:verify` Phase 2):**
- `total_orders_completed + total_orders_reserved == initial_stock - final_available_stock` tại `inventory_db`.
- `COUNT(outbox_events WHERE status = 'PENDING') == 0` sau khi hệ thống settle.
- Giá trị `inv:stock:{skuId}` trên Redis khớp với `available_stock` trên `inventory_db`.

---

## 9. Phạm vi Không thực hiện (Out of Scope for Phase 2)

Để giữ trọng tâm tuyệt đối vào **Distributed Systems Core & Reliability**, các hạng mục sau không nằm trong phạm vi Phase 2:
- Triển khai lên Kubernetes / Helm / Cloud managed services (toàn bộ chạy tái lập được qua Docker Compose & Testcontainers).
- Redis Cluster multi-shard hoặc tự cài đặt distributed consensus (Raft/Paxos).
- Hệ thống Auth đầy đủ (OAuth2/JWT/RBAC) — tiếp tục dùng mock user header/ID như Phase 1.
- Giỏ hàng đa sản phẩm phức tạp (Multi-SKU distributed lock trong 1 đơn hàng).
- Giao diện người dùng (Frontend UI).

---

## 10. Definition of Done (DoD) cho Phase 2

- [ ] Tách thành công ít nhất 2-3 services độc lập (`order-service`, `inventory-service`, `payment-service`) với PostgreSQL database riêng biệt (Database-per-Service).
- [ ] Hoàn thiện Redis Hot-Path Gate (Lua Script nguyên tử trừ kho + giới hạn user) và Distributed Rate Limiter + Idempotency Key.
- [ ] Hoàn thiện Kafka event pipeline kết hợp **Transactional Outbox** (`FOR UPDATE SKIP LOCKED`) và **Inbox Pattern** (`processed_events`), 0 dual-write bug.
- [ ] Hoàn thiện **Saga & Compensating Transactions** xử lý các case lỗi thanh toán, hết hạn TTL, và hoàn trả đồng bộ tồn kho giữa PostgreSQL và Redis.
- [ ] Tích hợp **Bulkhead** bảo vệ HikariCP dưới Virtual Threads, xử lý ổn định I/O delay từ `payment-service`.
- [ ] Tích hợp **Observability** (Micrometer Tracing / `traceId` xuyên HTTP–Outbox–Kafka, Prometheus metrics, Grafana hoặc actuator endpoints).
- [ ] 100% Integration Tests & Failure Injection / Chaos Tests (Testcontainers) pass, chứng minh Zero Overselling & Zero Lost Orders.
- [ ] Hoàn thành bộ k6 Benchmark Phase 2 và xuất bản báo cáo so sánh trực diện với Phase 1 tại `docs/learning/benchmark-phase-2.md` cùng các ADR (`ADR-0003`..`ADR-0006`).
