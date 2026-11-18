# ADR 0002: Sử dụng Spring Data JDBC & `JdbcTemplate` thay vì Spring Data JPA/Hibernate cho luồng Hotspot

- **Trạng thái:** Accepted
- **Ngày quyết định:** 2026-11-12
- **Người đề xuất:** Developer & Architecture Advisor

---

## 1. Ngữ cảnh (Context)
Trong các hệ thống xử lý giao dịch cao điểm (Flash Sale, High-Contention Inventory Reservation), độ chính xác của câu lệnh SQL, thời điểm gửi câu lệnh xuống Database, thời lượng giữ Connection và thời lượng giữ Lock trên dòng dữ liệu (`Row-Level Lock`) quyết định trực tiếp đến Throughput (RPS) và Latency (p99).

Spring Data JPA / Hibernate là giải pháp ORM phổ biến hàng đầu trong hệ sinh thái Java. Tuy nhiên, nó đi kèm nhiều cơ chế ngầm định phức tạp:
1. **First-Level Cache & Dirty Checking:** Hibernate trì hoãn việc flush câu lệnh SQL cho đến cuối transaction hoặc trước khi có query mới. Điều này làm mờ ranh giới thời điểm khóa thực sự được áp đặt lên cơ sở dữ liệu.
2. **Transaction Overhead:** Entity state tracking, proxy generation, và reflection overhead làm tăng thời gian giữ connection trong HikariCP và tạo thêm áp lực rác lên JVM Heap/GC.
3. **Nguy cơ truy vấn ẩn (Hidden Queries & N+1):** Lazy loading hoặc cascading fetch ngoài tầm kiểm soát có thể kéo thêm các truy vấn không mong muốn vào ngay giữa transaction đang giữ row-lock.
4. **Khó khăn khi benchmark:** Khó phân tách thời gian thực thi thuần túy của Database Engine với thời gian overhead do ORM framework sinh ra.

---

## 2. Quyết định (Decision)
Chúng tôi quyết định **sử dụng Spring Data JDBC kết hợp `JdbcTemplate` làm công nghệ truy cập dữ liệu chính** cho toàn bộ luồng nghiệp vụ cốt lõi của FlashOrder:
1. Viết trực tiếp câu lệnh SQL (Native SQL) thông qua `JdbcTemplate` cho các thao tác nhạy cảm với hiệu năng:
   - `SELECT FOR UPDATE` (Pessimistic Locking với câu lệnh rõ ràng).
   - Atomic conditional update: `UPDATE inventory_items SET reserved_stock = reserved_stock + ? WHERE sku_id = ? AND (total_stock - reserved_stock) >= ?`.
   - Batch insert cho các bản ghi reservation và outbox events.
2. Quản lý schema database bằng **Flyway migration scripts**. Không phụ thuộc vào `ddl-auto` của Hibernate.
3. Sử dụng Java 25 **Records** làm Data Transfer Objects (DTO) và Domain Models bất biến, ánh xạ thủ công từ `ResultSet` bằng `RowMapper`.

---

## 3. Các phương án đã cân nhắc (Alternatives Considered)

### Phương án A: Spring Data JPA (Hibernate)
- *Ưu điểm:* Phát triển CRUD nhanh chóng, `@Version` hỗ trợ sẵn cho Optimistic Locking.
- *Nhược điểm:*
  - Khó kiểm soát thời điểm câu lệnh SQL thực sự chạm tới PostgreSQL.
  - Phức tạp khi thực hiện atomic update trực tiếp trên DB mà không tải Entity vào persistence context.
  - Khó giải thích sâu sắc về cơ chế tầng dưới khi phỏng vấn nếu bị phụ thuộc vào "phép thuật" của framework.
- *Kết luận:* **Bị bác bỏ** cho luồng hotspot.

### Phương án B: jOOQ
- *Ưu điểm:* Type-safe SQL, DSL mạnh mẽ, hiệu năng cao tương đương JDBC thuần.
- *Nhược điểm:* 
  - Yêu cầu cấu hình thêm bước code-generation từ database schema.
  - Đòi hỏi license nếu dùng các tính năng nâng cao, thêm một lớp abstraction không bắt buộc ở Phase 1.
- *Kết luận:* Cân nhắc cho tương lai, nhưng ở Phase 1 `JdbcTemplate` là tối giản và trực quan nhất.

### Phương án C: Raw JDBC thuần (`java.sql.Connection`, `PreparedStatement`)
- *Ưu điểm:* Không phụ thuộc thư viện, kiểm soát 100%.
- *Nhược điểm:* Quá nhiều boilerplate code (đóng mở connection, try-catch `SQLException`, convert exceptions).
- *Kết luận:* `JdbcTemplate` cung cấp sự cân bằng hoàn hảo: giữ nguyên quyền kiểm soát câu lệnh SQL thuần nhưng loại bỏ boilerplate và tận dụng cơ chế quản lý transaction của Spring.

---

## 4. Hệ quả & Đánh đổi (Consequences & Trade-offs)

### Mặt tích cực (Pros):
- **100% Minh bạch:** Mỗi câu lệnh SQL thực thi, mỗi vị trí đặt lock, và mỗi round-trip mạng đều do lập trình viên trực tiếp viết và kiểm soát.
- **Tối ưu hóa thời lượng giữ Lock:** Có thể thu hẹp transaction boundary xuống mức tối thiểu, nhả lock ngay khi hoàn tất update.
- **Giá trị phỏng vấn vượt trội:** Lập trình viên có thể mở file repository và chỉ rõ chính xác: *"Đây là câu lệnh SQL chạm DB, đây là cách Postgres xử lý row lock ngầm định, và đây là lý do chúng tôi kiểm tra `rowsAffected` thay vì đọc lại dữ liệu"*.

### Đánh đổi (Cons / Risks):
- **Tốn công viết code ánh xạ:** Phải tự viết các `RowMapper` để chuyển đổi `ResultSet` sang Java Record.
- **Không có tự động đồng bộ quan hệ phức tạp:** Mọi thao tác lưu cha-con (`Order` và `OrderItem`) phải được viết tường minh từng bước. (Tuy nhiên, điều này lại chính là mục tiêu học tập để hiểu rõ giao dịch database).
