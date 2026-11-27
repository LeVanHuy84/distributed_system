# FlashOrder - Tasks & Implementation Plan: Phase 2
## Distributed Flash Sale Engine, Redis Hot-Path & Event-Driven Reliability

Tài liệu này theo dõi chi tiết tiến độ triển khai từng Feature và Task nhỏ của Phase 2 theo quy trình chuẩn 8 bước (**Understand $\rightarrow$ Design $\rightarrow$ Plan $\rightarrow$ Implement $\rightarrow$ Test $\rightarrow$ Review $\rightarrow$ Fix $\rightarrow$ Verify**). Đặc tả kiến trúc và bất biến được định nghĩa tại [scope-phase-2.md](scope-phase-2.md).

> **Mốc đối chiếu:** Phase 1 (Modular Monolith) được lưu nguyên trạng tại Git tag `phase-1` trên GitHub.

---

## 📊 Bảng tổng quan tiến độ (Progress Dashboard)

| Feature | Mô tả | Trạng thái | Tasks hoàn thành |
| :--- | :--- | :---: | :---: |
| **Feature 1** | Multi-Module Refactoring, Infra & ADRs | ⚪ Not Started | 0/4 |
| **Feature 2** | Event Contracts, Tracing & Isolated DB Schemas | ⚪ Not Started | 0/4 |
| **Feature 3** | High-Throughput Edge Gate (`order-service` + Redis) | ⚪ Not Started | 0/4 |
| **Feature 4** | Transactional Outbox Relay & Kafka Pipeline | ⚪ Not Started | 0/3 |
| **Feature 5** | `inventory-service` (Inbox, TTL & Redis Reconciler) | ⚪ Not Started | 0/3 |
| **Feature 6** | `payment-service` & Saga Compensation Flow | ⚪ Not Started | 0/3 |
| **Feature 7** | Distributed & Chaos Integration Tests (Testcontainers) | ⚪ Not Started | 0/5 |
| **Feature 8** | Benchmark Phase 2 (`k6`) & So sánh Hiệu năng | ⚪ Not Started | 0/4 |

---

## 🗂️ Chi tiết từng Feature & Tasks

### Feature 1: Multi-Module Refactoring, Distributed Infra & ADRs
Tách dự án thành cấu trúc Multi-Module Gradle, chuẩn bị hạ tầng container phân tán tiết kiệm tài nguyên và chốt các Architecture Decision Records trước khi code.

- [x] **Task 1.1: Chuyển đổi cấu trúc sang Multi-Module Gradle**
  - **Mục tiêu:** Tổ chức codebase thành `platform/common-events`, `platform/common-observability`, `services/order-service`, `services/inventory-service`, `services/payment-service`.
  - **Acceptance Criteria (AC):**
    - [x] `settings.gradle.kts` và `build.gradle.kts` (root + subprojects) build sạch với `./gradlew build -x test`.
    - [x] Không có dependency chéo trực tiếp giữa `order-service`, `inventory-service` và `payment-service`.
    - [x] `Spotless` (Palantir Java Format) hoạt động đồng bộ trên toàn bộ subprojects.
- [x] **Task 1.2: Cấu hình Docker Compose Phân tán (`docker-compose.yml` & `docker-compose.bench.yml`)**
  - **Mục tiêu:** Khởi tạo hạ tầng gồm PostgreSQL 16 (3 logical DBs cô lập), Redis 7, và Apache Kafka (KRaft mode).
  - **Acceptance Criteria (AC):**
    - [x] Script init SQL tạo `order_db`, `inventory_db`, `payment_db` với 3 user/role phân quyền riêng biệt (service này không đọc được DB của service kia).
    - [x] Redis 7 bật AOF persistence (`appendonly yes`) và có healthcheck.
    - [x] Kafka chạy KRaft mode (không Zookeeper) với healthcheck sẵn sàng cho local dev và benchmark.
- [ ] **Task 1.3: Cấu hình Docker Profile `observability` (On-Demand)**
  - **Mục tiêu:** Thêm Prometheus, Grafana và Zipkin vào `docker-compose.yml` dưới `profiles: ["observability"]`.
  - **Acceptance Criteria (AC):**
    - [ ] `docker compose up -d` mặc định không bật observability stack (giữ RAM tối ưu khi chạy benchmark k6).
    - [ ] `docker compose --profile observability up -d` khởi động thành công Prometheus scrape metrics từ cả 3 services (`8080`, `8081`, `8082`) và Zipkin nhận trace.
- [x] **Task 1.4: Soạn thảo Architecture Decision Records (ADR-0004..0007)**
  - **Mục tiêu:** Phản biện tối thiểu 2 phương án và ghi nhận trade-offs cho các bài toán cốt lõi Phase 2 dưới `docs/adr/`.
  - **Acceptance Criteria (AC):**
    - [x] `ADR-0004`: Hot-Path Gate (Redis Atomic Lua Script vs Pure DB vs Redlock).
    - [x] `ADR-0005`: Event Publishing (Transactional Outbox `SKIP LOCKED` vs Debezium CDC vs Direct Kafka Publish).
    - [x] `ADR-0006`: Saga Pattern (Orchestration tại `order-service` vs Choreography thuần túy).
    - [x] `ADR-0007`: Virtual Threads Concurrency Control (Semaphore Bulkhead + Rate Limit vs Unbounded Virtual Threads).

---

### Feature 2: Event Contracts, Tracing Propagation & Isolated DB Schemas
Chuẩn hóa giao tiếp sự kiện, lan truyền `traceId` xuyên ranh giới Outbox/Kafka và dựng schema độc lập cho 3 database.

- [ ] **Task 2.1: Thiết kế Event Contracts & Envelope (`platform/common-events`)**
  - **Mục tiêu:** Định nghĩa immutable Java 25 Records cho `EventEnvelope` (`eventId`, `eventType`, `aggregateId`, `occurredAt`, `traceParent`, `payload`) và các domain events.
  - **Acceptance Criteria (AC):**
    - [ ] Đủ bộ sự kiện vòng đời: `OrderReservationRequested`, `StockReserved`, `StockReservationFailed`, `PaymentRequested`, `PaymentSucceeded`, `PaymentFailed`, `StockConfirmed`, `StockConfirmRejected`, `StockReleased`, `PaymentRefundRequested`, `PaymentRefunded`.
    - [ ] Unit tests kiểm tra serialize/deserialize JSON (Jackson) chuẩn xác cho toàn bộ event types.
- [ ] **Task 2.2: Xây dựng Shared Observability & Trace Context Helper (`platform/common-observability`)**
  - **Mục tiêu:** Tích hợp Micrometer Tracing (OpenTelemetry bridge) và tiện ích giữ mạch `traceparent` qua bảng `outbox_events` và Kafka Headers.
  - **Acceptance Criteria (AC):**
    - [ ] Tự động log `traceId` và `spanId` trong pattern console/structured logs ở cả 3 services.
    - [ ] Có helper extract `traceparent` từ current span để lưu vào DB và inject lại vào Kafka ProducerRecord Headers khi Outbox Relay chạy ở thread khác.
- [ ] **Task 2.3: Hiện thực Flyway Migrations cho 3 DBs độc lập**
  - **Mục tiêu:** Thiết lập schema `order_db`, `inventory_db`, `payment_db` bằng Flyway tại từng service riêng.
  - **Acceptance Criteria (AC):**
    - [ ] `order_db`: `orders` (mở rộng trạng thái Saga, `idempotency_key`, `version`), `outbox_events`, `idempotency_keys`.
    - [ ] `inventory_db`: `inventory_items`, `stock_reservations` (thêm `redis_restored`), `processed_events`, `outbox_events`.
    - [ ] `payment_db`: `payment_transactions` (`UNIQUE (order_id)`), `processed_events`, `outbox_events`.
    - [ ] Tất cả bảng `outbox_events` đều có composite index trên `(status, created_at)`.
- [ ] **Task 2.4: Xây dựng `OutboxJdbcRepository` & `InboxJdbcRepository` (Spring JDBC)**
  - **Mục tiêu:** Cung cấp tầng persistence thuần `JdbcTemplate` cho Outbox và Idempotent Consumer.
  - **Acceptance Criteria (AC):**
    - [ ] `OutboxJdbcRepository` hỗ trợ insert trong cùng local transaction và query batch với `SELECT ... FOR UPDATE SKIP LOCKED`.
    - [ ] `InboxJdbcRepository` thực hiện `INSERT INTO processed_events ... ON CONFLICT (event_id) DO NOTHING`, trả về `boolean` xác định event đã xử lý trước đó hay chưa.

---

### Feature 3: High-Throughput Edge Gate (`order-service` + Redis)
Đưa luồng tiếp nhận đơn hàng Flash Sale lên Redis để đạt `sub-20ms` ở edge và bảo vệ PostgreSQL khỏi bão request.

- [ ] **Task 3.1: Hiện thực Distributed Rate Limiter (Redis)**
  - **Mục tiêu:** Chặn request vượt ngưỡng theo `userId` / IP bằng Redis Sliding Window hoặc Token Bucket (Lua Script).
  - **Acceptance Criteria (AC):**
    - [ ] Trả HTTP `429 Too Many Requests` kèm header `Retry-After` khi vượt quota.
    - [ ] Có cấu hình bật/tắt hoặc nới ngưỡng linh hoạt cho kịch bản benchmark k6 khi cần đo trần hệ thống.
- [ ] **Task 3.2: Hiện thực API `Idempotency-Key` Guard**
  - **Mục tiêu:** Đảm bảo client retry hoặc double-submit đồng thời với cùng `Idempotency-Key` không tạo đơn hàng thứ 2 và không trừ kho 2 lần.
  - **Acceptance Criteria (AC):**
    - [ ] Dùng Redis atomic `SET NX EX` đánh dấu trạng thái `IN_PROGRESS` kèm `request_hash`.
    - [ ] Trả HTTP `409 Conflict` nếu cùng key đang xử lý song song hoặc bị gửi lại với payload khác `request_hash`.
    - [ ] Trả lại cached HTTP status & body (`202` / `409`) nếu key đã hoàn tất trước đó.
- [ ] **Task 3.3: Hiện thực Redis Atomic Reservation Lua Script (`reserve_stock.lua`)**
  - **Mục tiêu:** Kiểm tra quota user + tồn kho nóng và giữ chỗ nguyên tử trong 1 round-trip Redis.
  - **Acceptance Criteria (AC):**
    - [ ] Kiểm tra `inv:user_qty:{skuId}:{userId} + qty <= max_per_user` và `inv:stock:{skuId} >= qty`.
    - [ ] Giảm `inv:stock:{skuId}`, tăng `inv:user_qty`, ghi hash `inv:res:{orderId}`, và `ZADD inv:res:expiry <expiresAtMs> <orderId>`.
    - [ ] Trả về các mã trạng thái rõ ràng: `OK`, `OUT_OF_STOCK`, `USER_LIMIT_EXCEEDED`, `SKU_NOT_INITIALIZED`.
- [ ] **Task 3.4: Hiện thực Flash-Sale Order API (`POST /api/v2/orders`, `GET /api/v2/orders/{id}`)**
  - **Mục tiêu:** Chấp nhận nhanh đơn hàng (`202 Accepted`), lưu `orders` + `outbox_events` nguyên tử trên `order_db`.
  - **Acceptance Criteria (AC):**
    - [ ] Nếu Redis trả `OUT_OF_STOCK`, trả ngay `409 Conflict` mà **không** mở connection tới `order_db`.
    - [ ] Nếu bước commit `order_db` thất bại sau khi Redis đã trừ, thực hiện **compensating rollback** hoàn trả ngay slot trên Redis (chạy Lua script `release_stock.lua` idempotent).
    - [ ] Trả `202 Accepted` kèm `orderId`, `status = PENDING_RESERVATION` và `Location` header.

---

### Feature 4: Transactional Outbox Relay & Reliable Kafka Pipeline
Đảm bảo không có lỗi Dual-Write khi phát sự kiện giữa DB và Kafka, đồng thời bảo vệ HikariCP Pool dưới tải Virtual Threads.

- [ ] **Task 4.1: Xây dựng Outbox Relay Poller (`SKIP LOCKED`)**
  - **Mục tiêu:** Định kỳ quét `outbox_events` trạng thái `PENDING` ở mỗi service để đẩy lên Kafka.
  - **Acceptance Criteria (AC):**
    - [ ] Sử dụng `SELECT ... FOR UPDATE SKIP LOCKED LIMIT ?` để an toàn nếu chạy nhiều instance poller song song.
    - [ ] Đọc `traceparent` từ record outbox và gắn vào Kafka `ProducerRecord.headers()` trước khi gửi.
    - [ ] Cập nhật `status = 'PUBLISHED'`, `published_at` sau khi Kafka ack thành công; tăng `retry_count` và đánh dấu `FAILED` nếu vượt ngưỡng retry tối đa.
- [ ] **Task 4.2: Cấu hình Kafka Producer, Consumer & Dead Letter Topic (`*.DLT`)**
  - **Mục tiêu:** Thiết lập pipeline tin cậy At-Least-Once và cách ly message lỗi.
  - **Acceptance Criteria (AC):**
    - [ ] Producer bật `acks=all`, `enable.idempotence=true`, đặt Kafka message key nhất quán (`orderId` cho vòng đời đơn / `skuId` khi cần tuần tự theo SKU).
    - [ ] Consumer dùng `MANUAL_IMMEDIATE` acknowledgment (chỉ commit offset sau khi DB transaction + Inbox đã commit).
    - [ ] Cấu hình `DefaultErrorHandler` với backoff và `DeadLetterPublishingRecoverer` đẩy poison pill message sang `*.DLT` sau N lần retry thất bại.
- [ ] **Task 4.3: Áp dụng Semaphore Bulkhead & Tuning HikariCP cho Virtual Threads**
  - **Mục tiêu:** Khắc phục triệt để lỗi `HikariCP connection-timeout` từng xảy ra ở Phase 1 khi 1.000 Virtual Threads lao vào DB.
  - **Acceptance Criteria (AC):**
    - [ ] Giới hạn số lượng concurrent DB executions qua Semaphore Bulkhead (ví dụ `max-concurrent-calls` khớp với `hikari.maximum-pool-size = 20`) và fail-fast với `503 Service Unavailable` kèm metric đếm số lần bulkhead reject.
    - [ ] Kiểm soát Kafka consumer concurrency phù hợp với pool DB của `inventory-service` và `order-service`.

---

### Feature 5: `inventory-service` (Inbox, TTL Sweeper & Redis-DB Reconciliation)
Đảm bảo PostgreSQL (`inventory_db`) giữ vai trò Source of Truth tuyệt đối và đồng bộ nhất quán cuối cùng với Redis.

- [ ] **Task 5.1: Hiện thực Inbox Consumer cho `OrderReservationRequested`**
  - **Mục tiêu:** Ghi nhận reservation bền vững vào `inventory_db` bằng `ATOMIC_UPDATE` kế thừa từ Phase 1.
  - **Acceptance Criteria (AC):**
    - [ ] Kiểm tra `processed_events` trong cùng transaction trước khi trừ kho.
    - [ ] Nếu `ATOMIC_UPDATE` thành công -> tạo `stock_reservations` (`RESERVED`) + ghi `StockReserved` vào `outbox_events`.
    - [ ] Nếu kho nguồn thực tế không đủ (edge drift) -> ghi `StockReservationFailed` vào `outbox_events` để Saga hủy đơn.
- [ ] **Task 5.2: Hiện thực Dual-Layer TTL Sweeper (Redis Orphaned + DB Expired)**
  - **Mục tiêu:** Không bao giờ để kẹt tồn kho ảo trên Redis hoặc `reserved_stock` trong DB.
  - **Acceptance Criteria (AC):**
    - [ ] **Redis Orphaned Sweeper:** Quét `inv:res:expiry` với score `< now - gracePeriod`; nếu reservation không tồn tại hoặc đã hủy trong DB -> hoàn lại `inv:stock:{skuId}` một cách idempotent.
    - [ ] **DB TTL Worker:** Nâng cấp từ Phase 1 để khi giải phóng reservation hết hạn (`RESERVED -> EXPIRED`), đồng thời khôi phục tồn kho trên Redis và đánh dấu `redis_restored = TRUE` (chống cộng đúp 2 lần).
- [ ] **Task 5.3: Hiện thực Stock Warm-up & Reconciliation Job**
  - **Mục tiêu:** Nạp tồn kho từ `inventory_db` lên Redis khi khởi động / bắt đầu Flash Sale và phát hiện/sửa drift định kỳ.
  - **Acceptance Criteria (AC):**
    - [ ] Endpoint/Command nội bộ `POST /internal/inventory/sync-redis` (hoặc startup runner) đồng bộ `available_stock` sang `inv:stock:{skuId}`.
    - [ ] Reconciliation worker định kỳ đối soát tổng `available_stock` DB vs `inv:stock` Redis + các reservation đang in-flight, xuất metric `inventory_redis_drift` lên Prometheus.

---

### Feature 6: `payment-service` & Saga Orchestration / Compensation
Hoàn chỉnh vòng đời thanh toán phân tán và giải quyết triệt để kịch bản tranh chấp Payment vs TTL bằng Compensating Transactions.

- [ ] **Task 6.1: Xây dựng `payment-service` Độc lập**
  - **Mục tiêu:** Service xử lý thanh toán & hoàn tiền idempotent với độ trễ I/O cấu hình được (`50–200ms`).
  - **Acceptance Criteria (AC):**
    - [ ] Tiêu thụ `PaymentRequested` (Inbox deduplication) -> mô phỏng gọi gateway với delay/failure rate cấu hình qua YAML -> lưu `payment_transactions` -> phát `PaymentSucceeded` hoặc `PaymentFailed` qua Outbox.
    - [ ] Tiêu thụ `PaymentRefundRequested` -> cập nhật `REFUNDED` idempotent -> phát `PaymentRefunded`.
- [ ] **Task 6.2: Hiện thực Saga Orchestrator & Monotonic Order State Machine (`order-service`)**
  - **Mục tiêu:** Điều phối chuyển trạng thái đơn hàng an toàn ngay cả khi Kafka event đến trễ hoặc sai thứ tự.
  - **Acceptance Criteria (AC):**
    - [ ] State machine tường minh cho `OrderStatus`: `PENDING_RESERVATION` $\rightarrow$ `RESERVED` $\rightarrow$ `PAYMENT_PENDING` $\rightarrow$ `COMPLETED` / `CANCELLED` / `REFUND_REQUIRED` $\rightarrow$ `REFUNDED`.
    - [ ] API kích hoạt thanh toán (`POST /api/v2/orders/{id}/pay`) chỉ cho phép khi đơn ở trạng thái `RESERVED` (hoặc cấu hình auto-pay ngay sau `StockReserved` cho kịch bản Flash Sale).
    - [ ] Từ chối cập nhật lùi trạng thái khi event đến out-of-order (ví dụ nhận `StockReserved` khi đơn đã `CANCELLED` $\rightarrow$ tự động phát lệnh release bù).
- [ ] **Task 6.3: Hiện thực Saga Compensation cho Kịch bản Hết hạn & Tranh chấp Confirm**
  - **Mục tiêu:** Xử lý mọi lỗi từng phần giữa `Order`, `Inventory` và `Payment`.
  - **Acceptance Criteria (AC):**
    - [ ] Khi `PaymentSucceeded`, `order-service` gửi lệnh/event yêu cầu `inventory-service` thực hiện `confirmReservation`.
    - [ ] Nếu `inventory-service` trả `StockConfirmRejected` (do TTL vừa hết hạn trước đó) $\rightarrow$ `order-service` chuyển đơn sang `REFUND_REQUIRED` và tự động phát `PaymentRefundRequested` tới `payment-service` $\rightarrow$ hoàn tất về `REFUNDED`.
    - [ ] Khi `PaymentFailed` $\rightarrow$ phát lệnh giải phóng reservation về `inventory-service` (hoàn cả DB và Redis) và chuyển đơn sang `CANCELLED`.

---

### Feature 7: Distributed Integration & Chaos/Failure Injection Tests (Testcontainers)
Chứng minh độ tin cậy phân tán dưới các lỗi thực tế (network/broker/service crash, duplicate delivery, out-of-order).

- [ ] **Task 7.1: Dựng `DistributedTestBase` (Testcontainers + Awaitility)**
  - **Mục tiêu:** Khởi tạo môi trường test tích hợp gồm PostgreSQL (3 DBs), Redis 7, và Kafka KRaft.
  - **Acceptance Criteria (AC):**
    - [ ] Container dùng chung tối ưu tốc độ chạy test (`@ServiceConnection` hoặc dynamic properties).
    - [ ] Sử dụng `Awaitility` kiểm chứng các trạng thái Eventual Consistency thay cho `Thread.sleep()`.
- [ ] **Task 7.2: Test Concurrency & Idempotency cho Redis Lua Gate**
  - **AC:** Bắn 500 threads đồng thời tranh 100 stock trên Redis $\rightarrow$ Đúng 100 thành công, 400 `OUT_OF_STOCK`, `inv:stock == 0`; bắn 50 requests cùng `Idempotency-Key` $\rightarrow$ Chỉ đúng 1 đơn hàng được tạo.
- [ ] **Task 7.3: Test Inbox Deduplication & Out-of-Order Events**
  - **AC:** Phát trùng event `OrderReservationRequested` 5 lần vào Kafka $\rightarrow$ `reserved_stock` chỉ tăng đúng 1 lần; bắn `StockReserved` sau khi đơn đã `CANCELLED` $\rightarrow$ kích hoạt hoàn kho bù thành công.
- [ ] **Task 7.4: Chaos Test — Kafka Broker Outage & Crash giữa Redis và DB**
  - **AC:** Dừng tạm container Kafka trong lúc tạo đơn $\rightarrow$ `outbox_events` giữ lại `PENDING`; bật lại Kafka $\rightarrow$ 100% đơn được xử lý sang `inventory_db` không mất bản ghi nào. Giả lập DB rollback sau khi Redis đã trừ $\rightarrow$ tồn kho Redis được khôi phục nguyên vẹn.
- [ ] **Task 7.5: Chaos Test — Payment vs TTL Race & Poison Pill Message**
  - **AC:** Mô phỏng thanh toán thành công ngay sau khi TTL hết hạn $\rightarrow$ Saga tự hoàn tất quy trình `REFUNDED`; gửi 1 malformed JSON message vào topic $\rightarrow$ message lỗi đi vào `.DLT`, các message hợp lệ phía sau vẫn xử lý bình thường.

---

### Feature 8: Benchmark Phase 2 (`k6`) & Báo cáo So sánh Hiệu năng
Đo đạc định lượng bước nhảy vọt từ Monolith (Phase 1) sang Kiến trúc Phân tán (Phase 2).

- [ ] **Task 8.1: Viết Bộ kịch bản `k6` Phase 2**
  - **Mục tiêu:** Tạo 5 kịch bản tải trong `benchmarks/k6/phase2/` đo cả Edge Latency (`202`/`409`) lẫn thời gian hội tụ cuối cùng.
  - **Acceptance Criteria (AC):**
    - [ ] `P2-01` Hotspot High Parity (1.000 VUs, 100 SKU, 30s) — đối chiếu trực diện với Phase 1.
    - [ ] `P2-02` Extreme Contention Parity (1.000 VUs, 1 SKU, 500 stock, 10s).
    - [ ] `P2-03` Flash Sale Scale-Out (5.000–10.000 VUs, 10 SKU, 30s).
    - [ ] `P2-04` Slow Payment I/O (1.000 VUs, payment delay `150ms`) — đo `Virtual Threads + Bulkhead` vs `Platform Threads`.
    - [ ] `P2-05` Burst Spike & Backpressure (0 $\rightarrow$ 5.000 VUs trong 5s, duy trì 60s).
- [ ] **Task 8.2: Tích hợp `bench:p2:*` và `bench:p2:verify` vào `Taskfile.yml`**
  - **AC:** Tự động hóa pipeline: Reset 3 DBs + Flush/Warm-up Redis + Tạo Kafka topics $\rightarrow$ Warm-up $\rightarrow$ Chạy k6 $\rightarrow$ Chờ Outbox/Kafka settle $\rightarrow$ Kiểm chứng bất biến khớp 100% giữa Redis, `order_db`, `inventory_db`, và `payment_db`.
- [ ] **Task 8.3: Thực hiện Đo lường So sánh (Phase 2 vs Phase 1 & Virtual Threads I/O)**
  - **AC:** Thu thập đầy đủ số liệu RPS, `p50/p95/p99` tại Edge, End-to-End persistence lag, Kafka Consumer Lag, và tỷ lệ lỗi hệ thống (`SysErr = 0`).
- [ ] **Task 8.4: Xuất bản Báo cáo `docs/learning/benchmark-phase-2.md` & Cập nhật `README.md`**
  - **AC:** Có bảng đối chiếu trực tiếp với số liệu Phase 1 (`1,323 RPS / 1,786ms` và `1,563 RPS / 2,304ms`), phân tích trade-off Redis vs Postgres, và bằng chứng định lượng về Virtual Threads + Bulkhead.
