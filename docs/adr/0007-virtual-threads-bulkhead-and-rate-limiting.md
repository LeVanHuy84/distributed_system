# ADR 0007: Kiểm soát Concurrency cho Java 25 Virtual Threads bằng Rate Limiter & Semaphore Bulkhead

- **Trạng thái:** Accepted
- **Ngày quyết định:** 2026-11-27
- **Người đề xuất:** Developer & Architecture Advisor
- **Liên quan:** [benchmark-phase-1.md](../learning/benchmark-phase-1.md), [scope-phase-2.md](../scope-phase-2.md)

---

## 1. Ngữ cảnh (Context)

Một trong những phát hiện thực nghiệm quan trọng nhất của Phase 1 ([benchmark-phase-1.md](../learning/benchmark-phase-1.md), mục 4) là việc bật **Java 25 Virtual Threads (`spring.threads.virtual.enabled=true`) một cách không kiểm soát đã làm xấu tail latency và gây sập pool kết nối DB**:
- Trên kịch bản *Hotspot High* (`1.000 VUs`, `100 SKU`, HikariCP `maximum-pool-size = 20`, `connection-timeout = 5000ms`):
  - Với `ATOMIC_UPDATE`, `Platform Threads` (`200` worker threads) có `p95 = 906.67 ms`, `p99 = 1,018.00 ms`; trong khi `Virtual Threads` tăng vọt lên `p95 = 1,786.47 ms`, `p99 = 2,619.71 ms`.
  - Với `PESSIMISTIC`, `Virtual Threads` cho phép cả `1.000` luồng lao vào tranh `20` slots HikariCP cùng lúc, khiến hàng đợi vượt quá `5,000 ms` và gây ra **`4,644` lỗi `HikariCP timeout`** (trong khi `Platform Threads` có `0` lỗi nhờ giới hạn tự nhiên ở 200 Tomcat worker threads).
  - Với `OPTIMISTIC` ở *Extreme Contention*, `Virtual Threads` làm retry storm trầm trọng hơn, khiến throughput tụt một nửa (`954.06 RPS` so với `1,968.75 RPS` của `Platform Threads`).

Nguyên nhân gốc rễ: Virtual Threads giúp mở rộng concurrency ở tầng JVM cực rẻ, nhưng lại vô tình **gỡ bỏ "bulkhead tự nhiên"** của Platform Thread Pool trước các tài nguyên giới hạn cứng như HikariCP (`20` connections) và PostgreSQL row-locks. Tuy nhiên, nếu tắt Virtual Threads, hệ thống sẽ bị nghẽn thread ngay khi `payment-service` thực hiện các tác vụ I/O mạng bên ngoài (`50–200ms`).

---

## 2. Quyết định (Decision)

Chúng tôi quyết định **giữ Java 25 Virtual Threads ở cả 3 microservices**, đồng thời thiết lập **3 lớp kiểm soát concurrency tường minh (Defense-in-Depth)**:

1. **Lớp 1 — Distributed Rate Limiter tại Edge (`order-service` + Redis):**
   - Giới hạn tần suất request theo `userId` / IP bằng Redis Sliding Window / Token Bucket Lua Script, trả `429 Too Many Requests` + `Retry-After` trước khi chạm tới tài nguyên nội bộ.
2. **Lớp 2 — Semaphore Bulkhead bảo vệ tầng DB (`platform/common-observability`):**
   - Áp dụng **Semaphore Bulkhead** (không dùng Fixed Thread Pool để tránh mất lợi thế Virtual Threads và giữ được context tracing) giới hạn số luồng Virtual Threads được phép đồng thời bước vào các thao tác JDBC/Transaction xuống PostgreSQL (tương thích với `hikari.maximum-pool-size`, kèm acquire timeout ngắn để **fail-fast** với `503 Service Unavailable` thay vì treo 5.000ms chờ HikariCP).
   - Xuất metric số lần bulkhead từ chối và số permits đang sử dụng lên Prometheus.
3. **Lớp 3 — Bounded Kafka Consumer Concurrency (Backpressure):**
   - Cấu hình `concurrency` của Kafka Listener Containers và `max.poll.records` tương xứng với kích thước HikariCP pool của `inventory-service` và `order-service`, để khi traffic burst `5.000–10.000 VUs`, đột biến tải được hấp thụ êm ái qua **Kafka Consumer Lag** thay vì làm ngạt PostgreSQL.

---

## 3. Các phương án đã cân nhắc (Alternatives Considered)

### Phương án A: Tắt Virtual Threads, quay về Platform Threads cố định
- *Ưu điểm:* Không cần tự cài đặt bulkhead trước DB; Tomcat 200 worker threads mặc định làm nhiệm vụ admission control.
- *Nhược điểm:* Khi `payment-service` (hoặc service gọi external API) có độ trễ mạng `50–200ms`, `200` Platform Threads nhanh chóng bị block hết dù CPU nhàn rỗi, gây giới hạn cứng throughput (`~200 / 0.15s ≈ 1,333 RPS`) và tốn bộ nhớ stack (~1MB/thread).
- *Kết luận:* **Bị bác bỏ** vì làm mất khả năng mở rộng cho workload I/O-bound thực tế.

### Phương án B: Tăng `hikari.maximum-pool-size` lên `200–500` để khớp với Virtual Threads
- *Ưu điểm:* Giảm lỗi timeout khi xin connection từ HikariCP.
- *Nhược điểm:* Chuyển nút cổ chai trực tiếp vào PostgreSQL Server. Hàng trăm kết nối active cùng lúc gây context-switching CPU, tranh chấp bộ nhớ buffer và sụt giảm throughput nghiêm trọng (theo công thức chuẩn của PostgreSQL/HikariCP: `pool_size ≈ core_count * 2 + effective_spindle_count`).
- *Kết luận:* **Bị bác bỏ** vì đi ngược nguyên lý tối ưu database connection pool.

### Phương án C: Virtual Threads + Redis Rate Limiter + Semaphore Bulkhead + Kafka Backpressure (Được chọn)
- *Ưu điểm:*
  - Tận dụng tối đa Virtual Threads cho các tác vụ I/O ngoài DB (Redis, Kafka network, Payment gateway delay `50–200ms`).
  - Bảo vệ tuyệt đối HikariCP (`20` connections) khỏi bão luồng, giữ `p95/p99` ổn định và fail-fast khi quá tải cục bộ.
- *Nhược điểm:* Cần tinh chỉnh đúng ngưỡng Semaphore permits và timeout (quá nhỏ sẽ gây `503` sớm dù DB còn rảnh; quá lớn sẽ quay lại hiện tượng xếp hàng HikariCP).

---

## 4. Hệ quả, Đánh đổi & Kịch bản kiểm chứng (Consequences & Verification)

### Mặt tích cực (Pros)
- **Sửa đúng điểm yếu của Phase 1:** Loại bỏ hiện tượng hàng nghìn Virtual Threads làm phình `p99` trước pool `20` DB connections.
- **Tách riêng 2 bài học phỏng vấn:** Chứng minh bằng số liệu thực nghiệm ở kịch bản `P2-04 (Slow Payment I/O)` rằng Virtual Threads kết hợp Bulkhead vượt trội hoàn toàn so với Platform Threads khi có network I/O ngoài DB.

### Rủi ro & Cách kiểm soát (Risks & Mitigations)
1. **Rủi ro cấu hình Semaphore Timeout dài hơn `hikari.connection-timeout`:**
   - *Mitigation:* Đặt `bulkhead.acquire-timeout` nhỏ (ví dụ `100–300ms`) và `permits` xấp xỉ `hikari.maximum-pool-size` (hoặc nhỉnh hơn một chút cho queue ngắn) để fail-fast rõ ràng thay vì chờ 5 giây.
2. **Rủi ro Thread Pinning trên Virtual Threads (Java 21–24):**
   - *Mitigation:* Dự án chạy trên **Java 25** (JEP 491 đã giải quyết triệt để pinning trong khối `synchronized`) và ưu tiên dùng `java.util.concurrent.Semaphore` / `ReentrantLock` cho bulkhead thay vì cơ chế khóa thô.
