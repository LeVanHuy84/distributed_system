# FlashOrder - Scope & Specification: Phase 1
## Core Concurrency & Database Engine (Modular Monolith)

---

## 1. Mục tiêu Giai đoạn 1 (Phase 1 Objectives)
Phase 1 tập trung giải quyết trọn vẹn và chuyên sâu bài toán **Concurrency & Data Integrity** tại tầng ứng dụng và cơ sở dữ liệu trước khi bước sang phân tán.

- **Mục tiêu kỹ thuật:**
  1. Xây dựng mô hình đặt hàng và giữ chỗ kho hàng (**Inventory Reservation with TTL**) đảm bảo không bao giờ bán âm (zero overselling).
  2. Hiện thực và so sánh công bằng **3 chiến lược kiểm soát tranh chấp (Concurrency Strategies)** trên cùng một nền tảng PostgreSQL + Spring JDBC (`JdbcTemplate`).
  3. Đo lường, phân tích hiệu năng và hiện tượng nghẽn (bottleneck) qua **6 kịch bản tải k6** khắt khe.
  4. So sánh hành vi và throughput giữa **Virtual Threads (Java 25)** và **Platform Threads** trong môi trường I/O blocking với HikariCP.
  5. Thiết lập bộ **Integration Test** tự động mô phỏng đa luồng tranh chấp cao bằng **Testcontainers**.

---

## 2. Kiến trúc & Phân ranh Module (Modular Monolith)

Toàn bộ Phase 1 chạy trong một Spring Boot application duy nhất nhưng tuân thủ ranh giới module nghiêm ngặt:

```
src/main/java/com/flashorder/
│
├── inventory/                  <-- Inventory Domain Module
│   ├── api/                    # REST Controllers / DTOs
│   ├── domain/                 # Models: Sku, StockReservation, StockLevel
│   ├── repository/             # JdbcTemplate Repositories (Locking queries)
│   ├── service/                # InventoryService & 3 Strategy Implementations
│   └── exception/              # InsufficientStockException, ConcurrencyConflictException
│
├── order/                      <-- Order Domain Module
│   ├── api/                    # Order Controller (Create, Cancel, Status)
│   ├── domain/                 # Models: Order, OrderItem, OrderStatus
│   ├── repository/             # OrderJdbcRepository
│   └── service/                # OrderService (Orchestrates reservation)
│
├── common/                     <-- Cross-cutting Concerns
│   ├── config/                 # HikariCP, ThreadPool / VirtualThread configuration
│   ├── lock/                   # ConcurrencyStrategy Enum & Interface
│   └── exception/              # Global Exception Handler
│
└── mock/                       <-- Mock Modules for Phase 1
    ├── user/                   # Mock User Authentication & Context
    └── payment/                # Mock Payment Gateway (Fast success / Instant fail)
```

> **Quy tắc ranh giới:** `order` module giao tiếp với `inventory` module thông qua Java Service Interface định nghĩa rõ ràng. Không cho phép `order` truy vấn trực tiếp bảng của `inventory` trong database. Điều này tạo điều kiện thuận lợi để tách thành Microservice ở Phase 2.

---

## 3. Mô hình Dữ liệu & Business Invariants

### 3.1. Business Invariants (Bất biến nghiệp vụ)
1. **Available Stock Invariant:** `available_stock = total_stock - reserved_stock`.
2. **Non-negative Invariant:** `available_stock >= 0` tại mọi thời điểm commit transaction.
3. **Reservation Expiration:** Mỗi reservation có một thời hạn tồn tại (ví dụ: TTL = 10 phút). Nếu đơn hàng không hoàn tất trước TTL, reservation sẽ bị giải phóng hoàn trả lại cho `available_stock`.
4. **Idempotent Reservation:** Cùng một `order_id` hoặc `idempotency_key` không được phép tạo nhiều hơn 1 reservation.

### 3.2. Bảng cơ sở dữ liệu cốt lõi (PostgreSQL)

```sql
-- Inventory SKU table
CREATE TABLE inventory_items (
    sku_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    total_stock INT NOT NULL CHECK (total_stock >= 0),
    reserved_stock INT NOT NULL DEFAULT 0 CHECK (reserved_stock >= 0),
    version BIGINT NOT NULL DEFAULT 0, -- Dành cho Optimistic Locking
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_stock_limit CHECK (reserved_stock <= total_stock)
);

-- Reservation table
CREATE TABLE stock_reservations (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    sku_id VARCHAR(64) NOT NULL REFERENCES inventory_items(sku_id),
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(32) NOT NULL, -- RESERVED, CONFIRMED, RELEASED, EXPIRED
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_order_sku UNIQUE (order_id, sku_id)
);

-- Orders table
CREATE TABLE orders (
    id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL, -- PENDING, COMPLETED, CANCELLED
    total_amount NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

---

## 4. Hiện thực 3 Chiến lược Concurrency (Stock Reservation)

Để tạo cơ sở so sánh khoa học, hệ thống hỗ trợ switch chiến lược qua cấu hình hoặc header request (`X-Concurrency-Strategy`):

### 1. Chiến lược 1: Optimistic Locking (với Version Column)
- **Cơ chế:**
  - Query: `SELECT sku_id, total_stock, reserved_stock, version FROM inventory_items WHERE sku_id = ?`
  - Ứng dụng tính toán: Kiểm tra `(total_stock - reserved_stock) >= quantity`.
  - Update: `UPDATE inventory_items SET reserved_stock = reserved_stock + ?, version = version + 1, updated_at = NOW() WHERE sku_id = ? AND version = ?`
  - Nếu `rows_affected == 0`: Ném `OptimisticLockingFailureException`. Thực hiện Retry với exponential backoff hoặc trả về lỗi cạnh tranh.
- **Dự đoán phỏng vấn:** Khi contention cao (10.000 VUs tranh chấp 100 sản phẩm), retry storm sẽ làm lãng phí CPU và DB connections.

### 2. Chiến lược 2: Pessimistic Locking (`SELECT FOR UPDATE`)
- **Cơ chế:**
  - Bắt đầu transaction.
  - Query: `SELECT sku_id, total_stock, reserved_stock FROM inventory_items WHERE sku_id = ? FOR UPDATE` (hoặc `FOR UPDATE NOWAIT` / `FOR UPDATE SKIP LOCKED` trong kịch bản đặc thù).
  - Ứng dụng kiểm tra điều kiện tồn kho.
  - Update: `UPDATE inventory_items SET reserved_stock = reserved_stock + ?, updated_at = NOW() WHERE sku_id = ?`
  - Tạo record `stock_reservations` và commit transaction (nhả lock).
- **Dự đoán phỏng vấn:** Đảm bảo 100% không bị xung đột retry, nhưng gây nghẽn hàng đợi (lock contention queue) trong PostgreSQL, dễ dẫn đến connection pool exhaustion và tăng latency (p99).

### 3. Chiến lược 3: Atomic Conditional SQL Update
- **Cơ chế:**
  - Thực hiện cập nhật trực tiếp tại tầng DB Engine chỉ trong một câu lệnh duy nhất:
    ```sql
    UPDATE inventory_items 
    SET reserved_stock = reserved_stock + ?, 
        updated_at = NOW() 
    WHERE sku_id = ? 
      AND (total_stock - reserved_stock) >= ?;
    ```
  - Kiểm tra `rows_affected`:
    - Nếu `rows_affected == 1`: Thành công -> Tạo record `stock_reservations`.
    - Nếu `rows_affected == 0`: Thất bại -> Hết hàng tồn khả dụng (OutOfStock).
- **Dự đoán phỏng vấn:** Tận dụng row-level lock ngầm định cực ngắn của Postgres trong lúc thực thi lệnh UPDATE, loại bỏ thời gian giữ lock do application round-trip, đem lại throughput cao nhất.

---

## 5. Kế hoạch Benchmark khắt khe (k6 & Metrics)

### 5.1. Ma trận Kịch bản Tải (Load Scenarios)

| Scenario | Mục đích | Tải mô phỏng | Tiêu chí đánh giá |
| :--- | :--- | :--- | :--- |
| **1. Baseline** | Đo hiệu năng chuẩn khi ít cạnh tranh | 100 VUs, 10.000 sản phẩm, 60s | Throughput trần (max RPS), baseline latency (p50/p95). |
| **2. Hotspot - Low** | Cạnh tranh nhẹ trên một nhóm SKU | 100 VUs, 100 sản phẩm, 30s | So sánh tỷ lệ retry của Optimistic vs thời gian đợi của Pessimistic. |
| **3. Hotspot - Medium** | Cạnh tranh đáng kể | 500 VUs, 100 sản phẩm, 30s | Điểm bắt đầu xuất hiện nghẽn HikariCP connection pool. |
| **4. Hotspot - High** | Cạnh tranh rất cao | 1.000 VUs, 100 sản phẩm, 30s | Thử thách khả năng chịu tải cao nhất, tỷ lệ error/timeout. |
| **5. Extreme Contention** | Gần như mọi request tranh 1 SKU duy nhất | 1.000 VUs, 1 sản phẩm (stock=100), 10s | Đo chính xác hiện tượng lock contention, CPU load, deadlock. |
| **6. Sustained Load** | Kiểm tra độ ổn định và memory leak | 200 VUs, nhiều SKU, 5 phút | Ổn định connection pool, JVM GC behavior, memory footprint. |

### 5.2. Nguyên tắc Benchmark công bằng
1. **Môi trường đồng nhất:** Cùng phiên bản PostgreSQL, cấu hình HikariCP cố định (`maximumPoolSize = 20`, `connectionTimeout = 5000ms`), cùng tài nguyên Docker limits (CPU/RAM).
2. **Trạng thái sạch:** Reset database về cùng một snapshot dữ liệu mẫu trước mỗi lần chạy.
3. **Warm-up:** Chạy warm-up script 10-15s trước khi ghi nhận metrics chính thức.
4. **Randomized Order:** Mỗi scenario được chạy ít nhất 3 lần, hoán đổi thứ tự giữa các chiến lược để tránh sai số cache.
5. **Đo tách biệt:** Tách riêng metric thời gian xử lý HTTP API và thời gian thực thi câu lệnh tại Database.
6. **Bật/Tắt Virtual Threads:** Thực hiện bộ test cả 2 chế độ:
   - Platform Threads tiêu chuẩn.
   - Java 25 Virtual Threads (`spring.threads.virtual.enabled=true`).

---

## 6. Chiến lược Testing (Testcontainers & Concurrent Tests)

1. **Unit Tests:** Kiểm tra logic tính toán stock, validate DTO, xử lý lỗi nghiệp vụ.
2. **Concurrent Integration Tests (Testcontainers + `ExecutorService` / `CompletableFuture`):**
   - Khởi tạo container PostgreSQL sạch bằng Testcontainers.
   - Khởi tạo stock = 100.
   - Bắn đồng thời 200 thread cùng lúc gọi hàm `reserveStock(sku, 1)`.
   - **Verification Assertions:**
     - Chính xác 100 requests thành công (status `RESERVED`).
     - Chính xác 100 requests thất bại với mã lỗi phù hợp (`INSUFFICIENT_STOCK` hoặc `CONCURRENCY_CONFLICT`).
     - Query DB kiểm tra `reserved_stock == 100` và `available_stock == 0`.
     - Tuyệt đối `reserved_stock <= total_stock`.

---

## 7. Definition of Done (DoD) cho Phase 1
- [ ] Codebase hoàn thiện bằng Java 25 + Spring Boot (Spring JDBC/`JdbcTemplate`).
- [ ] 3 chiến lược Concurrency hoạt động chính xác và có thể switch linh hoạt.
- [ ] 100% Concurrent Integration Tests pass với Testcontainers mà không có hiện tượng race condition hay overselling.
- [ ] Bộ k6 scripts hoàn thiện cho cả 6 kịch bản tải.
- [ ] Hoàn thành báo cáo benchmark so sánh (kèm bảng số liệu, biểu đồ so sánh Latency, RPS, Success Rate giữa 3 chiến lược và giữa Virtual Threads vs Platform Threads).
- [ ] Hoàn thiện các tài liệu trong `docs/adr/` và `docs/learning/postgres-concurrency.md`.
