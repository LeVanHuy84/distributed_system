# Ma trận Kiểm thử Đa luồng (Concurrent Testing Matrix - Phase 1)

Tài liệu này tổng hợp và giải thích dễ hiểu toàn bộ kiến trúc **Feature 6: Concurrent Testing Suite (Testcontainers)**, giúp bạn nắm vững cách test hoạt động, ý nghĩa từng test case và cách trả lời khi phỏng vấn.

- **Test Harness:** [`ConcurrentTestHelper.java`](../../src/test/java/com/flashorder/support/concurrency/ConcurrentTestHelper.java)
- **Result DTO:** [`ConcurrentExecutionResult.java`](../../src/test/java/com/flashorder/support/concurrency/dto/ConcurrentExecutionResult.java)
- **Integration Test Suite:** [`StockReservationConcurrencyIntegrationTest.java`](../../src/test/java/com/flashorder/inventory/concurrency/StockReservationConcurrencyIntegrationTest.java)

---

## 1. Tại sao phải viết Test Đa luồng bằng Testcontainers?

- **Unit Test (Mockito):** Chỉ kiểm tra logic gọi hàm tuần tự trên 1 luồng, không có tranh chấp dữ liệu thật.
- **In-memory DB (H2):** Không mô phỏng đúng cơ chế MVCC và khóa dòng (`SELECT ... FOR UPDATE`, row lock khi `UPDATE`) của PostgreSQL.
- **Testcontainers (PostgreSQL 16 thật) + 200 Virtual Threads:** Tạo ra va chạm thật sự tại tầng Database Engine để chứng minh hệ thống đạt **Zero Overselling** (tuyệt đối không bán quá tồn kho).

---

## 2. Cơ chế hoạt động của `ConcurrentTestHelper` (Starting Gun Pattern)

Nếu chỉ dùng vòng lặp `for` thông thường để chạy 200 request, luồng thứ 1 thường đã chạy xong trước khi luồng thứ 50 được tạo $\rightarrow$ **không tạo ra va chạm thật**.

Vì vậy, `ConcurrentTestHelper` dùng mô hình **Phát súng hiệu lệnh** với 3 `CountDownLatch`:

```text
Main Thread                         200 Virtual Threads
    │                                        │
    ├─ Tạo sẵn 200 threads ─────────────────►├─ readyLatch.countDown() (báo đã vào vạch xuất phát)
    │                                        ├─ startLatch.await()     (đứng chờ hiệu lệnh)
    ├─ readyLatch.await() (đợi đủ 200 đứa)   │
    ├─ startLatch.countDown() ─── 💥 BẮN! ──►├─ Cả 200 luồng lao vào DB cùng 1 mili-giây!
    │                                        ├─ Ghi kết quả vào ConcurrentLinkedQueue (thread-safe)
    │                                        └─ finally: doneLatch.countDown()
    ├─ doneLatch.await() (đợi tất cả xong)
    └─ Trả về ConcurrentExecutionResult
```

### 3 quy tắc vàng trong Harness:
1. **`readyLatch`:** Đảm bảo 100% số thread đã khởi tạo xong rồi mới xuất phát cùng lúc.
2. **`ConcurrentLinkedQueue`:** Lưu kết quả `successes` và `failures` an toàn đa luồng (không dùng `ArrayList` vì `ArrayList.add()` không thread-safe).
3. **`doneLatch.countDown()` nằm trong `finally`:** Dù thread thành công hay văng exception vẫn phải báo cáo đã xong, tránh làm test bị treo vĩnh viễn.

---

## 3. Ma trận Test Cases (`StockReservationConcurrencyIntegrationTest`)

Hiện tại suite có **4 nhóm kịch bản (8 lượt chạy test)** trên PostgreSQL thật:

| # | Tên Kịch bản (Method) | Chiến lược (`Strategy`) | Stock ban đầu | Số Threads | Qty / đơn | Thành công kỳ vọng | Thất bại kỳ vọng | Exception cho phép | Mục tiêu chứng minh |
| :-: | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :--- | :--- |
| **1** | `optimisticLocking_shouldPreventOverselling_underHighContention` | `OPTIMISTIC` | 100 | 200 | 1 | `1 .. 100` *(không cố định 100)* | `100 .. 199` | `InsufficientStockException` hoặc `ConcurrencyConflictException` | Không bán âm dưới áp lực cao; chứng minh hiện tượng *retry exhaustion* khi 200 threads tranh 1 row. |
| **2** | `serializedStrategies_shouldPreventOverselling_underHighContention` | `PESSIMISTIC` | 100 | 200 | 1 | **Đúng 100** | **Đúng 100** | Chỉ `InsufficientStockException` | `SELECT FOR UPDATE` tuần tự hóa giao dịch, bán hết sạch 100/100 không sót đơn, không oversell. |
| **3** | `serializedStrategies_shouldPreventOverselling_underHighContention` | `ATOMIC_UPDATE` | 100 | 200 | 1 | **Đúng 100** | **Đúng 100** | Chỉ `InsufficientStockException` | Câu lệnh `UPDATE ... WHERE` nguyên tử xử lý nhanh nhất, kết quả xác định 100% không oversell. |
| **4** | `shouldPreserveInventoryWhenConcurrentRequestsReserveMultipleQuantity` | `PESSIMISTIC` | 100 | 50 | 3 | **Đúng 33** (`99` sp) | **Đúng 17** | Chỉ `InsufficientStockException` | Khi còn lẻ `1` sản phẩm (`100 - 99`), đơn mua `3` sản phẩm thứ 34 phải bị chặn, không trừ âm. |
| **5** | `shouldPreserveInventoryWhenConcurrentRequestsReserveMultipleQuantity` | `ATOMIC_UPDATE` | 100 | 50 | 3 | **Đúng 33** (`99` sp) | **Đúng 17** | Chỉ `InsufficientStockException` | Tương tự #4 cho chiến lược Atomic SQL Update. |
| **6** | `shouldRejectAllReservationsWhenStockIsZero` | `OPTIMISTIC` | 0 | 50 | 1 | **0** | **Đúng 50** | Chỉ `InsufficientStockException` | Kiểm tra biên dưới (`stock = 0`): check tồn kho thất bại ngay, không sinh `ConcurrencyConflictException`. |
| **7** | `shouldRejectAllReservationsWhenStockIsZero` | `PESSIMISTIC` | 0 | 50 | 1 | **0** | **Đúng 50** | Chỉ `InsufficientStockException` | Tương tự #6 cho Pessimistic Locking. |
| **8** | `shouldRejectAllReservationsWhenStockIsZero` | `ATOMIC_UPDATE` | 0 | 50 | 1 | **0** | **Đúng 50** | Chỉ `InsufficientStockException` | Tương tự #6 cho Atomic Update. |

---

## 4. Bộ Bất biến Kiểm chứng (Invariant Assertions)

Sau mỗi kịch bản trên, hàm `assertInventoryInvariants(...)` kiểm tra trực tiếp xuống Database để đảm bảo các quy tắc toán học sau **luôn luôn đúng**:

1. **Tổng kho gốc không đổi:** `item.totalStock() == initialStock`.
2. **Bảo toàn tồn kho (Accounting Invariant):**
   $$\text{item.reservedStock()} = \sum (\text{quantity của các đơn thành công}) = \sum_{\text{DB}} \text{stock\_reservations.quantity (RESERVED)}$$
3. **Tồn khả dụng chính xác:** `item.availableStock() == initialStock - totalSuccessfulQuantity`.
4. **Zero Overselling:** `item.reservedStock() <= item.totalStock()` và `item.availableStock() >= 0`.
5. **Khớp số lượng bản ghi:** Số row `RESERVED` trong bảng `stock_reservations` bằng đúng `result.successCount()`.
6. **Tính duy nhất của kết quả:** Mọi `reservationId` và `orderId` trả về đều khác `null` và không bị trùng lặp.

---

## 5. 3 Câu hỏi "Tủ" khi Phỏng vấn về Bộ Test này

### Hỏi 1: Tại sao test `OPTIMISTIC` không assert cứng `successCount == 100` như `PESSIMISTIC` và `ATOMIC_UPDATE`?
- **Trả lời:** Trong `OptimisticLockingReservationStrategy`, khi 200 luồng cùng đọc `version = 0`, chỉ 1 luồng update thành công lên `version = 1`, 199 luồng còn lại bị `OptimisticLockConflictException` và phải retry (tối đa `MAX_RETRIES = 3`). Do contention quá cao (200 luồng vào cùng 1 dòng), nhiều luồng bị cạn hết 3 lượt retry trước khi mua được hàng và văng `ConcurrencyConflictException`. Vì vậy số đơn thành công hợp lệ nằm trong khoảng `1..100` — đây là đánh đổi cố hữu của Optimistic Locking dưới extreme contention (*Retry Storm*).

### Hỏi 2: Tại sao không gắn `@Transactional` lên method test trong Integration Test đa luồng?
- **Trả lời:** Vì `@Transactional` trên JUnit test chỉ gắn transaction với **luồng test chính** (và mặc định rollback cuối test). 200 Virtual Threads chạy ở các thread khác sẽ không nhìn thấy dữ liệu chưa commit của main thread, hoặc mỗi thread tự mở connection/transaction riêng. Do đó ta phải commit dữ liệu setup bằng `JdbcTemplate` trước và cô lập từng test bằng `skuId` sinh ngẫu nhiên (`SKU-CONC-<UUID>`).

### Hỏi 3: Tại sao cột `version` tăng theo `successCount` ở `OPTIMISTIC` nhưng lại bằng `0` ở `PESSIMISTIC` và `ATOMIC_UPDATE`?
- **Trả lời:** Chỉ câu SQL của Optimistic Locking có `SET ..., version = version + 1 WHERE version = ?` để phát hiện xung đột giữa lúc đọc và lúc ghi. Hai chiến lược còn lại dùng khóa ở tầng DB (`FOR UPDATE` hoặc row-level lock của `UPDATE` nguyên tử) nên không cần kiểm tra và tăng `version`.
