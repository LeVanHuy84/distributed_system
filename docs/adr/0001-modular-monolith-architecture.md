# ADR 0001: Khởi đầu bằng Kiến trúc Modular Monolith cho Phase 1

- **Trạng thái:** Accepted
- **Ngày quyết định:** 2026-11-12
- **Người đề xuất:** Developer & Architecture Advisor

---

## 1. Ngữ cảnh (Context)
Dự án **FlashOrder** nhằm giải quyết bài toán đặt hàng và giữ chỗ tồn kho chịu tải cao (Flash Sale: 10.000 VUs tranh chấp 100 sản phẩm). 

Trong thực tế, nhiều hệ thống vội vã chia nhỏ thành Microservices ngay từ ngày đầu (Day 1) khi chưa hiểu rõ ranh giới nghiệp vụ và chưa tối ưu hóa được năng lực xử lý tranh chấp của database đơn lẻ. Điều này dẫn đến sự phức tạp phân tán không cần thiết (network overhead, distributed tracing, network partitions, dual-write, distributed transactions) trong khi hiệu năng của từng node chưa được khai thác triệt để.

Mục tiêu cốt lõi của Phase 1 là:
- Tập trung 100% vào giải quyết bài toán Concurrency, Transaction Isolation, Row-level Locks, và Deadlock tại tầng Database Engine (PostgreSQL) và Application.
- Chuẩn bị ranh giới phân rã rõ ràng để có thể tách thành Microservices độc lập ở Phase 2 một cách tự nhiên.

---

## 2. Quyết định (Decision)
Chúng tôi quyết định triển khai **Phase 1 dưới dạng Modular Monolith**:
1. Tất cả các domain chạy chung trong một tiến trình Java (Spring Boot application duy nhất) và chia sẻ cùng một cơ sở dữ liệu PostgreSQL.
2. Hệ thống được phân chia thành các module ranh giới độc lập:
   - `inventory`: Quản lý SKU, kho hàng, reservation, các chiến lược khóa concurrency.
   - `order`: Quản lý vòng đời đơn hàng.
   - `mock`: Cung cấp mock payment gateway và user context.
   - `common`: Các tiện ích dùng chung (config, exceptions, base metrics).
3. **Quy tắc ranh giới bất khả xâm phạm:**
   - Module `order` **tuyệt đối không được truy vấn hoặc cập nhật trực tiếp** các bảng dữ liệu của module `inventory` trong database.
   - Giao tiếp giữa `order` và `inventory` phải thông qua **Java Service Interface** (`InventoryReservationService`) với các DTO (Java Records) bất biến.
   - Không dùng Foreign Key giữa bảng của `order` và bảng của `inventory` (hoặc chỉ dùng nếu có lý do ràng buộc nghiêm ngặt ở Phase 1, nhưng khuyến nghị quản lý bằng application logic để sẵn sàng tách DB ở Phase 2).

---

## 3. Các phương án đã cân nhắc (Alternatives Considered)

### Phương án A: Tách Microservices ngay từ đầu (Microservices from Day 1)
- *Ưu điểm:* Độc lập deploy, mô phỏng môi trường phân tán ngay lập tức.
- *Nhược điểm:* 
  - Phải xử lý ngay Saga, Outbox, Kafka, Network latency khi chưa có baseline về locking database.
  - Tốn tài nguyên máy phát triển, setup test phức tạp, khó debug race condition.
- *Kết luận:* **Bị bác bỏ** vì làm phân tán sự tập trung vào bài toán cốt lõi của Phase 1.

### Phương án B: Monolith truyền thống không ranh giới (Spaghetti Monolith)
- *Ưu điểm:* Viết code nhanh, join SQL giữa các bảng `orders` và `inventory_items` tùy tiện.
- *Nhược điểm:* 
  - Tạo ra sự phụ thuộc chặt chẽ (tight coupling).
  - Không thể tách ra Microservices ở Phase 2 mà không phải đập đi xây lại toàn bộ schema và code.
- *Kết luận:* **Bị bác bỏ** vì vi phạm nguyên tắc thiết kế hệ thống.

---

## 4. Hệ quả & Đánh đổi (Consequences & Trade-offs)

### Mặt tích cực (Pros):
- **Tập trung tối đa vào Concurrency:** Toàn bộ công sức được dồn vào việc đo đạc, benchmark 3 chiến lược locking và kiểm soát connection pool HikariCP.
- **Tối ưu vòng phản hồi (Feedback loop):** Chạy Integration Test với Testcontainers cực nhanh, kiểm tra đa luồng nội tại dễ dàng.
- **Đường lui kiến trúc rõ ràng (Evolutionary Architecture):** Khi tách service ở Phase 2, chỉ cần thay thế Java Service Interface bằng Feign Client / gRPC / Kafka Event mà không cần sửa core logic bên trong `inventory`.

### Đánh đổi (Cons / Risks):
- **Cần kỷ luật lập trình cao:** Không có rào cản vật lý ngăn cản lập trình viên tiêm (inject) repository của module này vào module kia. (Khắc phục bằng ArchUnit test hoặc code review nghiêm ngặt).
