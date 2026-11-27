# ADR 0006: Kiến trúc Saga Orchestration & Compensating Transactions cho Vòng đời Đơn hàng Phân tán

- **Trạng thái:** Accepted
- **Ngày quyết định:** 2026-11-27
- **Người đề xuất:** Developer & Architecture Advisor
- **Liên quan:** [ADR-0003](0003-scheduled-batch-worker-for-reservation-ttl.md), [scope-phase-2.md](../scope-phase-2.md), [OrderService.java](../../services/order-service/src/main/java/com/flashorder/order/service/OrderService.java)

---

## 1. Ngữ cảnh (Context)

Một giao dịch đặt hàng hoàn chỉnh trong FlashOrder liên quan tới 3 domain tách biệt database:
1. **`order-service`** (`order_db`): Tiếp nhận đơn, quản lý trạng thái đơn hàng.
2. **`inventory-service`** (`inventory_db` + Redis): Giữ chỗ có thời hạn (TTL), xác nhận trừ kho thật hoặc giải phóng kho.
3. **`payment-service`** (`payment_db`): Thu tiền và hoàn tiền (kèm độ trễ I/O mạng thực tế).

Ở Phase 1, `OrderService.payOrder` kết hợp với [ADR-0003](0003-scheduled-batch-worker-for-reservation-ttl.md) đã bộc lộ một kịch bản tranh chấp thực tế: người dùng thanh toán thành công nhưng ngay tích tắc đó TTL Worker vừa thu hồi reservation (`RELEASED`). Phase 1 chỉ mới ghi log `"Compensating refund required."` mà chưa có quy trình hoàn tiền thực sự. Khi phân tán qua Kafka, hệ thống còn phải đối mặt thêm với:
- Kho nguồn `inventory_db` từ chối giữ chỗ dù Redis vừa cho qua (do drift).
- Thanh toán thất bại hoặc timeout sau khi đã giữ chỗ kho.
- Kafka event đến trễ hoặc sai thứ tự (out-of-order).

---

## 2. Quyết định (Decision)

Chúng tôi quyết định sử dụng **Saga Orchestration đặt tại `order-service`** kết hợp **Monotonic Order State Machine** và giao tiếp bất đồng bộ qua Kafka (Outbox):

1. **`order-service` đóng vai trò Saga Orchestrator:**
   - Lưu trữ và điều khiển chuỗi chuyển trạng thái đơn hàng tại `orders`:
     `PENDING_RESERVATION` $\rightarrow$ `RESERVED` $\rightarrow$ `PAYMENT_PENDING` $\rightarrow$ `COMPLETED`
   - Các nhánh lỗi/bù trừ tường minh:
     - `StockReservationFailed` $\rightarrow$ `FAILED` (kèm hoàn slot Redis nếu cần).
     - `PaymentFailed` $\rightarrow$ phát lệnh giải phóng kho (`ReleaseStockRequested` / `OrderCancelled`) $\rightarrow$ `CANCELLED`.
     - `PaymentSucceeded` nhưng `inventory-service` trả `StockConfirmRejected` (do TTL hết hạn trước đó) $\rightarrow$ chuyển `REFUND_REQUIRED`, phát `PaymentRefundRequested` tới `payment-service` $\rightarrow$ `PaymentRefunded` $\rightarrow$ `REFUNDED`.
2. **Monotonic State Machine (Chống Event Out-of-Order):**
   - Sử dụng `UPDATE orders SET status = ?, version = version + 1 WHERE id = ? AND status = ?` (hoặc kiểm tra tập trạng thái tiền nhiệm hợp lệ).
   - Không bao giờ cho phép lùi trạng thái từ terminal state (`CANCELLED`, `COMPLETED`, `REFUNDED`). Nếu nhận được `StockReserved` khi đơn đã chuyển sang `CANCELLED` (do user/TTL hủy trước), Orchestrator từ chối tiến bước và phát bù lệnh giải phóng kho.
3. **Đồng bộ hoàn kho 2 tầng (PostgreSQL + Redis):**
   - Mọi kịch bản bù trừ giải phóng kho (`PaymentFailed`, `TTL Expired`, `OrderCancelled`) tại `inventory-service` đều hoàn trả `reserved_stock` trong PostgreSQL **và** hoàn trả `inv:stock:{skuId}` trên Redis đúng 1 lần (idempotent qua cờ `redis_restored`).

---

## 3. Các phương án đã cân nhắc (Alternatives Considered)

### Phương án A: Two-Phase Commit (2PC / XA Distributed Transaction)
- *Ưu điểm:* Đảm bảo tính nhất quán mạnh (ACID) xuyên 3 database mà không cần viết logic bù trừ.
- *Nhược điểm:*
  - Blocking protocol: giữ lock trên cả `order_db`, `inventory_db`, `payment_db` trong suốt thời gian chờ bước thanh toán (50–200ms+), gây thảm họa lock contention và chết hệ thống khi 1 node chậm/rớt.
  - Không hỗ trợ Redis và không phù hợp với kiến trúc High-Throughput Flash Sale.
- *Kết luận:* **Bị bác bỏ hoàn toàn.**

### Phương án B: Saga Choreography Thuần túy (Không có Orchestrator)
- *Ưu điểm:* Không có điểm điều phối trung tâm, các service tự lắng nghe sự kiện của nhau (`PaymentService` nghe `StockReserved`, `InventoryService` nghe `PaymentSucceeded`, v.v.).
- *Nhược điểm:*
  - Khi thêm các nhánh rẽ phức tạp như **Payment vs TTL Race $\rightarrow$ Refund Compensation** và **Out-of-order cancellation**, logic luồng nghiệp vụ bị xé lẻ rải rác qua 3 services, rất khó đọc hiểu, dễ tạo phụ thuộc vòng ngầm (cyclic dependency) giữa `inventory` và `payment`.
  - Khó trả lời câu hỏi debug *"Đơn hàng X hiện đang kẹt ở bước nào và vì sao?"* nếu không gom logic chuyển trạng thái về một nơi.
- *Kết luận:* **Bị bác bỏ** cho vòng đời 3 bên có nhánh hoàn tiền bù trừ.

### Phương án C: Saga Orchestration tại `order-service` (Được chọn)
- *Ưu điểm:*
  - Toàn bộ luồng nghiệp vụ và state machine nằm tập trung, rõ ràng tại `order-service`.
  - `inventory-service` và `payment-service` giữ được vai trò thuần túy là domain executor (nhận lệnh/sự kiện $\rightarrow$ thực thi idempotent $\rightarrow$ trả kết quả), không cần biết về sự tồn tại của nhau.
- *Nhược điểm:* `order-service` chịu trách nhiệm nhiều hơn về logic điều phối; cần bảo đảm `order-service` stateless khi scale (trạng thái Saga lưu bền vững trong `order_db`, không giữ in-memory).

---

## 4. Hệ quả, Đánh đổi & Kịch bản lỗi (Consequences & Failure Modes)

### Mặt tích cực (Pros)
- **Khép kín lỗ hổng Phase 1:** Tranh chấp giữa Payment và TTL không còn bị bỏ ngỏ ở mức log cảnh báo mà tự động kích hoạt quy trình `PaymentRefundRequested` $\rightarrow$ `REFUNDED`.
- **Dễ kiểm thử Chaos & Phỏng vấn:** Có thể vẽ chính xác state diagram và viết Testcontainers kiểm chứng từng nhánh rẽ lỗi.

### Rủi ro & Cách kiểm soát (Failure Modes & Mitigations)
1. **Nhận trùng lệnh bù trừ (`PaymentRefundRequested` bị redeliver):**
   - *Mitigation:* `payment-service` dùng Inbox table (`processed_events`) kết hợp ràng buộc trạng thái trên `payment_transactions` (`WHERE order_id = ? AND status = 'SUCCEEDED'`) để đảm bảo không bao giờ hoàn tiền 2 lần.
2. **Đơn bị treo ở trạng thái trung gian (`PENDING_RESERVATION` hoặc `PAYMENT_PENDING`) nếu event bị đẩy vào `.DLT`:**
   - *Mitigation:* Kết hợp TTL của reservation để thu hồi kho kịp thời và cho phép theo dõi trạng thái qua API `GET /api/v2/orders/{id}` + cảnh báo Prometheus khi có message rơi vào Dead Letter Topic.
