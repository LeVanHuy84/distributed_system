# ADR 0003: Cơ chế Thu hồi Tồn kho quá hạn (Reservation TTL) bằng Scheduled Batch Worker kết hợp Database CAS

- **Trạng thái:** Accepted
- **Ngày quyết định:** 2026-11-24
- **Người đề xuất:** Developer & Architecture Advisor

---

## 1. Ngữ cảnh (Context)
Trong hệ thống FlashOrder, khi người dùng bắt đầu quy trình đặt hàng, một bản ghi giữ chỗ (`StockReservation`) được tạo ra với trạng thái `RESERVED` và thời gian hết hạn (`expires_at`, mặc định 10 phút) để bảo đảm người mua có thời gian thanh toán.

Tuy nhiên, trong các sự kiện mở bán tải cao, phần lớn người dùng có thể bỏ dở thanh toán hoặc thoát ứng dụng giữa chừng. Nếu không có cơ chế tự động giải phóng các khoản giữ chỗ này, tồn kho sẽ bị "giam lỏng" ở cột `reserved_stock`, dẫn đến tình trạng hết hàng ảo (False Out-of-Stock) và gây tổn thất doanh thu.

Thách thức kỹ thuật đặt ra:
1. **Hiện tượng đột biến dồn ứ (Backlog Burst):** Hàng nghìn bản ghi có thể hết hạn trong cùng một khung thời gian ngắn, đe dọa làm cạn kiệt bộ nhớ Heap (OOM) và làm kiệt quệ connection pool HikariCP nếu quét toàn bộ vào bộ nhớ.
2. **Nguy cơ chạy đè luồng (Job Overlapping):** Nếu thời gian xử lý một đợt quét kéo dài hơn chu kỳ kích hoạt, nhiều worker sẽ chạy song song đè lên nhau, làm nhân đôi tải DB và gây nghẽn nghiêm trọng.
3. **Tranh chấp dữ liệu gay gắt (Critical Race Condition):** Xảy ra khi người dùng thanh toán đúng vào tích tắc cuối cùng (`T = 09:59.999`) mà Worker cũng quét trúng reservation đó. Nếu không có cơ chế phân xử nguyên tử, hệ thống sẽ gặp thảm họa bán âm (Overselling) hoặc trừ tiền của khách nhưng lại trả hàng về kho cho người khác mua mất.

---

## 2. Quyết định (Decision)
Chúng tôi quyết định hiện thực cơ chế thu hồi tồn kho quá hạn bằng mô hình **Spring `@Scheduled(fixedDelay)` kết hợp Database Batch Polling và Compare-And-Swap (CAS) ở tầng SQL**:

### 2.1. Điều khiển chu kỳ bằng `fixedDelay` thay vì `fixedRate`
- Sử dụng `@Scheduled(fixedDelayString = "${flashorder.worker.release-interval-ms:5000}")`.
- **Nguyên lý:** Timer chỉ bắt đầu đếm chu kỳ nghỉ **sau khi đợt xử lý trước đó kết thúc 100%**. Điều này loại bỏ hoàn toàn rủi ro hai luồng worker chạy chồng chéo (Job Overlapping) và bảo vệ an toàn cho HikariCP pool.

### 2.2. Phân lô (Batching) kết hợp Composite Index
- Truy vấn danh sách hết hạn theo từng mẻ nhỏ (`BATCH_SIZE = 100`) thông qua phương thức `findExpiredReservations(Instant now, int limit)`.
- Tối ưu hóa truy vấn bằng chỉ mục kết hợp:
  ```sql
  CREATE INDEX idx_reservations_status_expires ON stock_reservations(status, expires_at);
  ```
- Tránh hoàn toàn việc quét toàn bộ bảng (`Full Table Scan`) và không gây áp lực rác lên JVM Garbage Collector.

### 2.3. Ranh giới giao dịch độc lập từng phần tử (Transaction per Item)
- Worker đóng vai trò điều phối (Orchestrator). Vòng lặp duyệt qua danh sách hết hạn và gọi `service.releaseReservation(reservation.id())`.
- **Mỗi reservation được xử lý trong một Database Transaction riêng biệt** thông qua `TransactionTemplate`. Điều này đảm bảo:
  - Connection DB và Row Lock trên dòng dữ liệu được nhả ra ngay sau khi xử lý xong một đơn vị.
  - Lỗi xảy ra ở một reservation không làm rollback cả mẻ 100 đơn.

### 2.4. Phân xử tranh chấp bằng Database CAS (Compare-And-Swap)
- Điểm chốt chặn duy nhất (Single Source of Truth) giữa luồng Worker và luồng Confirm Payment là cột `status` của `stock_reservations`.
- Cả hai luồng đều cập nhật có điều kiện:
  - **Worker (Hủy đơn):**
    ```sql
    UPDATE stock_reservations SET status = 'RELEASED' WHERE id = ? AND status = 'RESERVED';
    ```
  - **Payment (Hoàn tất):**
    ```sql
    UPDATE stock_reservations SET status = 'CONFIRMED' WHERE id = ? AND status = 'RESERVED';
    ```
- Cơ chế Exclusive Row-Lock của PostgreSQL đảm bảo chỉ có 1 luồng duy nhất nhận được `rowsAffected = 1`.
- Luồng nào nhận `rowsAffected = 0` sẽ dừng lại ngay lập tức và không được phép can thiệp vào bảng tồn kho `inventory_items`. Nếu Worker đã thu hồi kho trước khi khách thanh toán thành công, hệ thống kích hoạt luồng bù hoàn tiền (Compensating Refund) và chuyển Order sang `CANCELLED`.

### 2.5. Cô lập ngoại lệ (Error Isolation)
- Từng lượt gọi trong vòng lặp của Worker được bọc trong khối `try-catch` riêng biệt. Nếu một reservation gặp sự cố mạng hoặc lỗi cục bộ, Worker ghi log `ERROR` và tiếp tục xử lý các phần tử kế tiếp, không để exception làm sập cả tiến trình chạy ngầm.

---

## 3. Các phương án đã cân nhắc (Alternatives Considered)

### Phương án A: Dựa vào Redis Key Expiration (TTL Keyspace Notifications)
- *Ưu điểm:* Độ trễ gần như tức thời khi key hết hạn.
- *Nhược điểm:* 
  - Cơ chế Pub/Sub của Redis là "fire-and-forget", không bảo đảm phân phát tin cậy (At-Least-Once Delivery). Nếu ứng dụng khởi động lại hoặc rớt kết nối đúng lúc key hết hạn, sự kiện sẽ bị biến mất vĩnh viễn $\rightarrow$ Tồn kho bị thất thoát.
  - Tăng độ phức tạp vận hành và thêm phụ thuộc bên ngoài khi Phase 1 đang tập trung vào Postgres engine.
- *Kết luận:* **Bị bác bỏ** cho Phase 1.

### Phương án B: Message Broker Delay Queue (RabbitMQ DLX / Scheduled Kafka Message)
- *Ưu điểm:* Kiến trúc Event-Driven tiêu chuẩn cho hệ thống phân tán lớn.
- *Nhược điểm:* 
  - Đưa thêm hạ tầng Message Broker vào Phase 1 là tối ưu hóa sớm (Premature Optimization) khi đang chạy Modular Monolith.
  - Vẫn phải xử lý bài toán race condition giữa Payment và Consumer tại tầng Database.
- *Kết luận:* **Bảo lưu cho Phase 2 (Microservices)**.

### Phương án C: Cơ chế Database Trigger hoặc Extension (pg_cron)
- *Ưu điểm:* Chạy trực tiếp bên trong Postgres, không tốn tài nguyên mạng giữa App và DB.
- *Nhược điểm:* 
  - Đưa logic nghiệp vụ vào database engine, vi phạm ranh giới kiến trúc.
  - Cực kỳ khó viết Automated Integration Test với Testcontainers.
- *Kết luận:* **Bị bác bỏ**.

---

## 4. Hệ quả & Đánh đổi (Consequences & Trade-offs)

### Mặt tích cực (Pros):
- **Độ tin cậy tối đa (Reliability & ACID):** Dữ liệu được bảo chứng bởi PostgreSQL ACID transaction. Không có rủi ro mất mát sự kiện khi hệ thống khởi động lại.
- **Triệt tiêu Race Condition:** Cơ chế CAS tại tầng DB ngăn chặn triệt để hiện tượng Overselling và Double-Release mà không cần phân tán distributed lock phức tạp.
- **Không phát sinh phụ thuộc (Zero External Dependencies):** Toàn bộ giải pháp hoạt động hoàn hảo với Spring Boot và PostgreSQL thuần túy.
- **Dễ dàng kiểm thử (Testability):** Có thể viết trọn vẹn Unit Test (với Mockito) và Integration Test (với Testcontainers) mô phỏng chính xác mọi tình huống tranh chấp dữ liệu.

### Đánh đổi (Cons / Risks):
- **Độ trễ thu hồi (Release Latency):** Tồn kho không được giải phóng chính xác từng mili-giây mà có độ trễ phụ thuộc vào chu kỳ quét (`fixedDelay`, ví dụ 5 giây). Đây là đánh đổi hoàn toàn chấp nhận được trong các hệ thống thương mại điện tử thực tế.
- **Tải định kỳ lên Database:** Worker định kỳ gửi query xuống DB. Tuy nhiên, nhờ chỉ mục `(status, expires_at)` và giới hạn `LIMIT 100`, chi phí CPU và I/O của database là không đáng kể (vài micro-giây khi rỗng).
- **Hạn chế khi mở rộng Multi-Instance (Horizontal Scaling):** Khi chạy nhiều instance ứng dụng ở Phase 2, nhiều worker có thể cùng quét một tập dữ liệu. Dù cơ chế CAS bảo đảm không có lỗi logic, nhưng sẽ phát sinh lãng phí CPU $\rightarrow$ Cần bổ sung ShedLock hoặc Database Row Skip Locked (`FOR UPDATE SKIP LOCKED`) khi mở rộng nhiều node.
