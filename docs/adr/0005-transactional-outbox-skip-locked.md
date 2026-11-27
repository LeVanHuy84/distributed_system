# ADR 0005: Đảm bảo Giao tiếp Sự kiện Tin cậy bằng Transactional Outbox (`FOR UPDATE SKIP LOCKED`) & Inbox Pattern

- **Trạng thái:** Accepted
- **Ngày quyết định:** 2026-11-27
- **Người đề xuất:** Developer & Architecture Advisor
- **Liên quan:** [ADR-0001](0001-modular-monolith-architecture.md), [ADR-0002](0002-spring-jdbc-over-jpa-for-concurrency-control.md), [scope-phase-2.md](../scope-phase-2.md)

---

## 1. Ngữ cảnh (Context)

Khi tách Modular Monolith thành 3 microservices có database riêng biệt (`order_db`, `inventory_db`, `payment_db`), chúng ta mất hoàn toàn khả năng dùng 1 local ACID transaction bao trùm cả `orders` và `stock_reservations`.

Ngay từ Phase 1, ngay cả trong cùng một ứng dụng, việc gọi tuần tự ghi reservation rồi lưu đơn hàng (`OrderService.createOrder`) đã tiềm ẩn rủi ro không đồng bộ nếu bước sau thất bại. Ở môi trường phân tán với Apache Kafka, nếu một service vừa cập nhật DB vừa gọi `kafkaTemplate.send(...)` trực tiếp sẽ gặp bài toán kinh điển **Dual-Write Bug**:
- DB commit thành công nhưng broker Kafka timeout / app crash trước khi gửi $\rightarrow$ **Mất sự kiện vĩnh viễn (Lost Order / Stuck Reservation)**.
- Gửi Kafka trước khi commit DB nhưng DB rollback $\rightarrow$ **Sự kiện ma (Phantom Event)** khiến service khác trừ kho cho đơn hàng không tồn tại.

Ngoài ra, khi dùng cơ chế bất đồng bộ qua DB Outbox + Kafka, context của **Distributed Tracing (`traceId`)** mặc định thường bị đứt đoạn ở bước poller đọc DB bằng thread riêng.

---

## 2. Quyết định (Decision)

Chúng tôi quyết định áp dụng **Transactional Outbox Pattern (Polling Publisher với `SKIP LOCKED`)** kết hợp **Idempotent Consumer (Inbox Pattern)** trên nền tảng `JdbcTemplate`:

1. **Outbox tại Producer (`order_db`, `inventory_db`, `payment_db`):**
   - Mọi thay đổi trạng thái nghiệp vụ phải ghi bản ghi `outbox_events` (`status = 'PENDING'`, `payload JSONB`, `trace_id` / `traceparent`) **trong cùng 1 local ACID transaction**.
   - Một Outbox Relay Poller định kỳ đọc batch theo truy vấn:
     `SELECT ... FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC FOR UPDATE SKIP LOCKED LIMIT ?`
   - Relay đọc `traceparent` từ row DB để inject vào Kafka `ProducerRecord` headers, gửi với `acks=all` và `enable.idempotence=true`, sau đó cập nhật `PUBLISHED`.
2. **Inbox (Deduplication) tại Consumer (`inventory_db`, `payment_db`, `order_db`):**
   - Do Outbox + Kafka cung cấp ngữ nghĩa **At-Least-Once Delivery** (message có thể bị gửi lặp nếu poller crash ngay sau khi Kafka ack nhưng trước khi kịp update `PUBLISHED`), mọi consumer bắt buộc kiểm tra và chèn `event_id` vào bảng `processed_events` (`INSERT ... ON CONFLICT (event_id) DO NOTHING`) **trong cùng transaction** với thao tác nghiệp vụ, và chỉ commit Kafka offset sau khi DB transaction hoàn tất (`MANUAL_IMMEDIATE`).
3. **Cách ly Poison Pill:**
   - Consumer retry có backoff giới hạn cho lỗi tạm thời, sau đó đẩy message lỗi sang Dead Letter Topic (`*.DLT`) để không làm nghẽn toàn bộ partition.

---

## 3. Các phương án đã cân nhắc (Alternatives Considered)

### Phương án A: Direct Kafka Publish + `try/catch` (hoặc `@TransactionalEventListener`)
- *Ưu điểm:* Độ trễ cực thấp (không qua bảng trung gian), ít code nhất.
- *Nhược điểm:* Không giải quyết được Dual-Write. Ngay cả `@TransactionalEventListener(phase = AFTER_COMMIT)` vẫn mất message nếu tiến trình JVM chết hoặc Kafka sập ngay sau thời điểm DB commit.
- *Kết luận:* **Bị bác bỏ** vì vi phạm bất biến *Zero Lost Orders*.

### Phương án B: Change Data Capture (Debezium + Kafka Connect)
- *Ưu điểm:* Đọc trực tiếp PostgreSQL WAL (Write-Ahead Log), độ trễ thấp hơn polling (~vài ms), không tốn truy vấn `SELECT` định kỳ lên DB.
- *Nhược điểm:*
  - Yêu cầu thêm cụm **Kafka Connect + Debezium Connector** và bật `wal_level=logical` + quản lý replication slot (nếu connector dừng lâu mà không dọn slot, WAL sẽ phình đầy đĩa Postgres).
  - Tăng đáng kể mức tiêu thụ RAM trên môi trường local 16GB và làm phức tạp setup Testcontainers.
- *Kết luận:* **Không chọn ở Phase 2** (nhưng thiết kế bảng `outbox_events` vẫn tương thích hoàn toàn để nâng cấp sang Debezium CDC sau này mà không cần sửa nghiệp vụ).

### Phương án C: Transactional Outbox Polling với `FOR UPDATE SKIP LOCKED` (Được chọn)
- *Ưu điểm:*
  - Chỉ dùng chính PostgreSQL + `JdbcTemplate` sẵn có, dễ hiểu, dễ debug và kiểm thử Chaos Test (tắt/bật Kafka bằng Testcontainers).
  - `SKIP LOCKED` cho phép chạy nhiều instance poller song song mà không khóa chéo hay xử lý trùng batch.
- *Nhược điểm:* Độ trễ relay phụ thuộc chu kỳ poll (ví dụ `50–200ms`) và tạo thêm tải `SELECT/UPDATE` nhẹ lên bảng `outbox_events`.

---

## 4. Hệ quả, Đánh đổi & Kịch bản lỗi (Consequences & Failure Modes)

### Mặt tích cực (Pros)
- **Loại bỏ 100% Dual-Write Bug:** DB commit và ý định phát event là một đơn vị nguyên tử.
- **Xuyên suốt Distributed Trace:** Nhờ lưu `traceparent` vào `outbox_events`, Jaeger có thể vẽ trọn vẹn 1 trace từ HTTP API $\rightarrow$ DB Outbox $\rightarrow$ Kafka $\rightarrow$ Inventory/Payment Consumers.

### Rủi ro & Cách kiểm soát (Failure Modes & Mitigations)
1. **Duplicate Delivery khi Poller crash sau bước Kafka Ack:**
   - *Mitigation:* Inbox table (`processed_events`) chặn hoàn toàn việc thực thi nghiệp vụ lần thứ 2 cho cùng `event_id`.
2. **Bảng `outbox_events` và `processed_events` phình to theo thời gian:**
   - *Mitigation:* Composite index `(status, created_at)` giúp quét `PENDING` cực nhanh bất kể số row `PUBLISHED`; trong vận hành thực tế có thể bổ sung job dọn/archive các bản ghi `PUBLISHED` cũ hơn N ngày.
3. **Mất thứ tự tương đối nếu tách partition tùy tiện:**
   - *Mitigation:* Đặt Kafka message key là `orderId` cho các sự kiện vòng đời đơn hàng để đảm bảo mọi event của cùng 1 đơn đi vào đúng 1 partition theo đúng thứ tự phát hành.
