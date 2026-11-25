# FlashOrder - Tasks & Implementation Plan: Phase 1
## Core Concurrency & Database Engine (Modular Monolith)

Tài liệu này theo dõi chi tiết tiến độ triển khai từng Feature và Task nhỏ của Phase 1 theo quy trình chuẩn 8 bước (Understand $\rightarrow$ Design $\rightarrow$ Plan $\rightarrow$ Implement $\rightarrow$ Test $\rightarrow$ Review $\rightarrow$ Fix $\rightarrow$ Verify).

---

## 📊 Bảng tổng quan tiến độ (Progress Dashboard)

| Feature | Mô tả | Trạng thái | Tasks hoàn thành |
| :--- | :--- | :---: | :---: |
| **Feature 1** | Database Foundation & Flyway Migration | 🟢 Completed | 3/3 |
| **Feature 2** | Domain Models & Spring JDBC Repositories | 🟢 Completed | 4/4 |
| **Feature 3** | 3 Concurrency Reservation Strategies | 🟢 Completed | 5/5 |
| **Feature 4** | Order Orchestration & Mock Services | 🟢 Completed | 4/4 |
| **Feature 5** | Reservation TTL & Auto-Release Worker | ⚪ Not Started | 0/3 |
| **Feature 6** | Concurrent Integration Testing (Testcontainers) | ⚪ Not Started | 0/5 |
| **Feature 7** | Benchmark Suite (k6) & Performance Analysis | ⚪ Not Started | 0/4 |

---

## 🗂️ Chi tiết từng Feature & Tasks

### Feature 1: Database Foundation & Flyway Migration
Thiết lập schema cơ sở dữ liệu PostgreSQL chuẩn mực, các chỉ mục (indexes) và ràng buộc toàn vẹn dữ liệu (constraints).

- [x] **Task 1.1: Thiết kế Bảng `inventory_items`**
  - **Mục tiêu:** Lưu trữ tồn kho của SKU với 2 cột `total_stock` và `reserved_stock`.
  - **Acceptance Criteria (AC):**
    - [x] Có ràng buộc `CHECK (total_stock >= 0)`.
    - [x] Có ràng buộc `CHECK (reserved_stock >= 0)`.
    - [x] Có ràng buộc `CHECK (reserved_stock <= total_stock)` để ngăn chặn bán âm ở tầng DB engine.
    - [x] Có cột `version BIGINT DEFAULT 0` để phục vụ Optimistic Locking.
- [x] **Task 1.2: Thiết kế Bảng `stock_reservations` & `orders`**
  - **Mục tiêu:** Lưu lịch sử giữ chỗ với hạn giờ (TTL) và bảng đơn hàng cơ bản.
  - **Acceptance Criteria (AC):**
    - [x] `stock_reservations` có ràng buộc `UNIQUE (order_id, sku_id)` để đảm bảo tính Idempotent khi retry.
    - [x] Có index `idx_reservations_status_expires` trên `(status, expires_at)` để phục vụ background worker quét tìm bản ghi hết hạn cực nhanh.
    - [x] Bảng `orders` lưu trạng thái đơn hàng (`PENDING`, `COMPLETED`, `CANCELLED`).
- [x] **Task 1.3: Hiện thực Flyway Migration Scripts**
  - **Mục tiêu:** Viết file SQL migration và chạy thử nghiệm.
  - **Acceptance Criteria (AC):**
    - [x] Tạo `src/main/resources/db/migration/V1__init_schema.sql`.
    - [x] Tạo `src/main/resources/db/migration/V2__seed_inventory.sql` (chèn sẵn một số SKU mẫu với stock = 100 và stock = 10.000 để phục vụ test).
    - [x] Chạy migration thành công bằng lệnh `task db:up` hoặc qua `./gradlew.bat test`.

---

### Feature 2: Domain Models & Spring Data JDBC Repositories
Xây dựng tầng truy cập dữ liệu thuần `JdbcTemplate` theo [ADR-0002](file:///d:/VsCode/Java/distributed_system/docs/adr/0002-spring-jdbc-over-jpa-for-concurrency-control.md), không dùng ORM/Hibernate.

- [x] **Task 2.1: Xây dựng Domain Models bằng Java 25 Records**
  - **Mục tiêu:** Tạo các immutable value objects cho `InventoryItem`, `StockReservation`, `Order`, `ReservationStatus`.
  - **AC:** Sử dụng Java Records, immutability, validation cơ bản trong compact constructor.
- [x] **Task 2.2: Hiện thực `InventoryJdbcRepository`**
  - **Mục tiêu:** Tạo repository với `JdbcTemplate` và `RowMapper`.
  - **AC:** Cung cấp các method: `findBySkuId(String skuId)`, `insertReservation(...)`, `updateStatus(...)`.
- [x] **Task 2.3: Hiện thực `OrderJdbcRepository`**
  - **Mục tiêu:** Lưu trữ và cập nhật trạng thái đơn hàng.
- [x] **Task 2.4: Viết Integration Test kiểm tra Repository**
  - **AC:** Chạy test với Testcontainers PostgreSQL, xác nhận CRUD hoạt động chính xác.

---

### Feature 3: Hiện thực 3 Chiến lược Concurrency (Stock Reservation)
Trái tim kỹ thuật của Phase 1: Triển khai và so sánh 3 cơ chế kiểm soát tranh chấp.

- [x] **Task 3.1: Thiết kế Strategy Pattern Interface**
  - **Mục tiêu:** Tạo interface `StockReservationStrategy` với method `ReservationResult reserve(String skuId, UUID orderId, int quantity)`.
  - **AC:** Định nghĩa enum `ConcurrencyStrategy` (`OPTIMISTIC`, `PESSIMISTIC`, `ATOMIC_UPDATE`).
- [x] **Task 3.2: Strategy 1 - Optimistic Locking**
  - **Mục tiêu:** Hiện thực kiểm tra tồn kho dựa trên cột `version` và câu lệnh `UPDATE ... WHERE version = ?`.
  - **AC:** Có cơ chế retry với exponential backoff khi gặp `OptimisticLockingFailureException`.
- [x] **Task 3.3: Strategy 2 - Pessimistic Locking**
  - **Mục tiêu:** Hiện thực khóa dòng bằng `SELECT ... FOR UPDATE`.
  - **AC:** Đặt transaction boundary hợp lý, cấu hình `lock_timeout` để tránh treo connection.
- [x] **Task 3.4: Strategy 3 - Atomic Conditional SQL Update**
  - **Mục tiêu:** Thực hiện trừ stock trực tiếp tại database bằng câu lệnh `UPDATE ... WHERE stock >= requested`.
  - **AC:** Kiểm tra `rows_affected == 1` để quyết định thành công, không cần lock tường minh.
- [x] **Task 3.5: Strategy Router qua Request Header**
  - **AC:** Cho phép đổi chiến lược động thông qua HTTP Header `X-Concurrency-Strategy`.

---

### Feature 4: Order Orchestration & Mock Boundaries
Kết nối quy trình đặt hàng và giữ chỗ kho hàng trong kiến trúc Modular Monolith ([ADR-0001](file:///d:/VsCode/Java/distributed_system/docs/adr/0001-modular-monolith-architecture.md)).

- [x] **Task 4.1: Hiện thực `OrderService`**
  - **AC:** Gọi sang `InventoryService.reserve()`, tạo đơn hàng `PENDING` nếu giữ kho thành công, rollback nếu thất bại.
- [x] **Task 4.2: Mock Payment & Mock User Services**
  - **AC:** Tạo mock service giả lập thanh toán (thành công tức thì hoặc giả lập delay).
- [x] **Task 4.3: REST API Controllers**
  - **AC:** Endpoint `POST /api/v1/orders` (tạo đơn & giữ chỗ), `GET /api/v1/orders/{id}`.
- [x] **Task 4.4: Global Exception Handling & Error DTOs**
  - **AC:** Chuyển đổi `InsufficientStockException`, `ConcurrencyConflictException` thành HTTP response rõ ràng (409 Conflict, 400 Bad Request).

---

### Feature 5: Reservation TTL & Auto-Release Worker
Giải quyết bài toán: Không giữ hàng tồn kho vĩnh viễn khi người dùng bỏ dở thanh toán.

- [x] **Task 5.1: Thiết kế cơ chế Release Stock**
  - **AC:** Viết method hoàn trả kho nguyên tử: `UPDATE inventory_items SET reserved_stock = reserved_stock - ?`.
- [x] **Task 5.2: Xây dựng Scheduled Worker**
  - **AC:** Định kỳ (ví dụ mỗi 30s) quét các reservation có `status = 'RESERVED' AND expires_at < NOW()`.
- [x] **Task 5.3: Xử lý tranh chấp giữa Release Worker và Confirm Payment**
  - **AC:** Đảm bảo worker không hủy nhầm một đơn hàng vừa kịp thanh toán thành công.

---

### Feature 6: Concurrent Testing Suite (Testcontainers)
Chứng minh hệ thống không bao giờ bán âm (Zero Overselling) dưới áp lực đa luồng.

- [x] **Task 6.1: Dựng Test Harness Đa luồng (`ConcurrentTestHelper`)**
  - **AC:** Sử dụng `CountDownLatch` / `CompletableFuture` để đồng loạt kích hoạt 200 threads cùng 1 thời điểm.
- [x] **Task 6.2: Test Concurrent cho Optimistic Locking**
- [x] **Task 6.3: Test Concurrent cho Pessimistic Locking**
- [x] **Task 6.4: Test Concurrent cho Atomic Conditional Update**
- [x] **Task 6.5: Kiểm chứng Bất biến (Invariant Assertions)**
  - **AC:** Khởi tạo stock = 100, 200 requests tranh chấp $\rightarrow$ Đúng 100 thành công, đúng 100 thất bại, tồn khả dụng cuối cùng bằng 0, không có exception unhandled.

---

### Feature 7: Benchmark Suite (k6) & Phân tích Hiệu năng
Vũ khí tạo ấn tượng mạnh nhất trong CV và phỏng vấn.

- [ ] **Task 7.1: Viết 6 Kịch bản k6 scripts (JavaScript)**
  - [ ] Baseline (100 VUs, 10.000 SKU, 60s)
  - [ ] Hotspot Low (100 VUs, 100 SKU, 30s)
  - [ ] Hotspot Medium (500 VUs, 100 SKU, 30s)
  - [ ] Hotspot High (1.000 VUs, 100 SKU, 30s)
  - [ ] Extreme Contention (1.000 VUs, 1 SKU, 10s)
  - [ ] Sustained Load (200 VUs, 5 phút)
- [ ] **Task 7.2: Tích hợp lệnh Benchmark vào `Taskfile.yml`**
  - **AC:** Tự động hóa: Reset DB $\rightarrow$ Seed data $\rightarrow$ Warm-up 10s $\rightarrow$ Chạy k6 $\rightarrow$ Xuất summary.
- [ ] **Task 7.3: Thực hiện đo lường so sánh**
  - [ ] So sánh 3 chiến lược concurrency (RPS, Latency p50/p95/p99, Error rate).
  - [ ] So sánh Platform Threads vs Virtual Threads (Java 25).
- [ ] **Task 7.4: Xuất bản Báo cáo Benchmark tại `docs/learning/benchmark-phase-1.md`**
