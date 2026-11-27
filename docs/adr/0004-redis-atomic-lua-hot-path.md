# ADR 0004: Đưa Luồng Giữ chỗ Hot-Path lên Redis Atomic Lua Script

- **Trạng thái:** Accepted
- **Ngày quyết định:** 2026-11-27
- **Người đề xuất:** Developer & Architecture Advisor
- **Liên quan:** [ADR-0003](0003-scheduled-batch-worker-for-reservation-ttl.md), [scope-phase-2.md](../scope-phase-2.md), [benchmark-phase-1.md](../learning/benchmark-phase-1.md)

---

## 1. Ngữ cảnh (Context)

Ở Phase 1, kết quả đo lường bằng `grafana/k6` ([benchmark-phase-1.md](../learning/benchmark-phase-1.md)) chứng minh **Zero Overselling 100%**, nhưng cũng chỉ ra giới hạn vật lý của PostgreSQL đơn lẻ khi chịu tranh chấp cao:
- Với `ATOMIC_UPDATE` trên kịch bản *Hotspot High* (`1.000 VUs`, `100 SKU`), hệ thống đạt trần **`1,323.49 RPS`** với `p95 = 1,786.47 ms`, `p99 = 2,619.71 ms`.
- Với *Extreme Contention* (`1.000 VUs`, `1 SKU`, `500 stock`), throughput dừng ở **`1,562.97 RPS`** và `p99 = 2,304.07 ms`.
- Mọi request hết hàng (`15,732` requests trả `409 Conflict` trong 10 giây) vẫn phải mở DB transaction, chiếm kết nối HikariCP (`maximum-pool-size: 20`) và chờ row-level lock trên PostgreSQL.

Khi hướng tới bài toán Flash Sale `5.000–10.000+ VUs` với độ trễ tiếp nhận mục tiêu `< 50ms` ở edge, chúng ta không thể đẩy toàn bộ lưu lượng tranh chấp thô xuống thẳng tầng đĩa/WAL của PostgreSQL.

---

## 2. Quyết định (Decision)

Chúng tôi quyết định **tách luồng đặt hàng thành 2 pha (Edge Admission vs Durable Persistence)** và sử dụng **Redis Atomic Lua Script (Single-Master / Standalone)** làm cổng kiểm soát hot-path phía trước PostgreSQL:

1. **Redis Atomic Lua Script (`reserve_stock.lua`):** Thực hiện trọn vẹn trong **1 network round-trip** và được Redis thực thi tuần tự đơn luồng (single-threaded atomicity):
   - Kiểm tra hạn mức mua của user trên SKU (`inv:user_qty:{skuId}:{userId}`).
   - Kiểm tra tồn kho khả dụng `inv:stock:{skuId} >= quantity`.
   - Nếu không đủ: trả `OUT_OF_STOCK` ngay lập tức để `order-service` trả `409 Conflict` (**0 kết nối DB, 0 message Kafka**).
   - Nếu đủ: giảm `inv:stock:{skuId}`, tăng quota user, ghi `inv:res:{orderId}` và đưa vào ZSET `inv:res:expiry` kèm timestamp hết hạn.
2. **Cấu hình độ bền & bộ nhớ Redis bắt buộc:**
   - Bật AOF persistence (`--appendonly yes --appendfsync everysec`).
   - Ép chính sách bộ nhớ `--maxmemory-policy noeviction` để tuyệt đối không tự động xóa key tồn kho hay reservation khi đầy RAM.
3. **PostgreSQL vẫn là Source of Truth cuối cùng:**
   - Kết quả giữ chỗ trên Redis được ghi nhận bất đồng bộ xuống `order_db` (Outbox) $\rightarrow$ Kafka $\rightarrow$ `inventory_db` (`ATOMIC_UPDATE` kế thừa từ Phase 1).
   - Bổ sung **Redis Orphaned Slot Sweeper** (tuyến phòng thủ tương tự [ADR-0003](0003-scheduled-batch-worker-for-reservation-ttl.md) ở tầng Redis) và **Reconciliation Job** để chữa lành mọi sai lệch (drift) nếu node app crash hoặc Redis restart.

---

## 3. Các phương án đã cân nhắc (Alternatives Considered)

### Phương án A: Giữ nguyên PostgreSQL `ATOMIC_UPDATE` (Scale dọc hoặc Partition/Shard DB)
- *Ưu điểm:* Tính nhất quán mạnh (Strong Consistency / ACID) tuyệt đối tại 1 điểm duy nhất, không cần đồng bộ 2 hệ thống lưu trữ.
- *Nhược điểm:*
  - Chi phí disk `fsync` WAL và row-lock serialization trên 1 hot SKU giới hạn trần ở ~`1,500 RPS`.
  - Mọi request đến sau khi hết hàng vẫn tiêu tốn HikariCP connection và CPU PostgreSQL.
- *Kết luận:* **Bị bác bỏ** làm tầng tuyến đầu, nhưng giữ lại `ATOMIC_UPDATE` làm lớp phòng thủ thứ hai tại `inventory_db`.

### Phương án B: Distributed Lock (Redlock / Redis `SET NX`) bọc quanh thao tác đọc–kiểm tra–ghi DB
- *Ưu điểm:* Tránh nhiều request vào DB cùng lúc cho cùng 1 SKU.
- *Nhược điểm:*
  - Tốn tối thiểu 3–4 bước (acquire lock $\rightarrow$ query/update DB $\rightarrow$ release lock), làm tăng latency tổng và giữ lock lâu hơn nhiều so với 1 Lua script chạy in-memory.
  - Có bẫy cổ điển về TTL expiry khi JVM dính Stop-The-World GC pause hoặc clock drift khiến 2 client cùng tưởng mình đang giữ lock (vi phạm mutual exclusion nếu thiếu fencing token).
- *Kết luận:* **Bị bác bỏ** vì phức tạp không cần thiết và hiệu năng kém hơn Lua atomic counter.

### Phương án C: Redis Atomic Lua Script + Async Persistence (Được chọn)
- *Ưu điểm:*
  - Thực thi in-memory đơn luồng trong Redis, độ trễ `< 1–5 ms`, xả tải tức thì toàn bộ traffic `409 Conflict` ngay tại RAM.
  - Gộp kiểm tra user quota + trừ stock + ghi TTL expiry ZSET vào 1 thao tác nguyên tử không cần lock tường minh.
- *Nhược điểm:* Chấp nhận Eventual Consistency giữa Redis và PostgreSQL; cần thiết kế kỹ cơ chế hoàn kho idempotent và đối soát drift.

---

## 4. Hệ quả, Đánh đổi & Kịch bản lỗi (Consequences & Failure Modes)

### Mặt tích cực (Pros)
- **Tách biệt tốc độ tiếp nhận và tốc độ ghi đĩa:** Edge API trả `202 Accepted` hoặc `409 Conflict` trong vài mili-giây thay vì chờ DB row-lock.
- **Chống gom hàng (Anti-scalping) ngay tại edge:** Kiểm tra `inv:user_qty` ngay trong cùng Lua script mà không phát sinh thêm query DB.

### Rủi ro & Cách kiểm soát (Failure Modes & Mitigations)
1. **Crash giữa bước Redis trừ kho và `order_db` commit:**
   - *Mitigation:* Catch exception ở `order-service` để gọi ngay Lua script hoàn trả slot (`release_stock.lua`); đồng thời **Redis Orphaned Sweeper** định kỳ quét `inv:res:expiry` thu hồi các slot mồ côi quá grace period.
2. **Redis restart và mất tối đa 1s dữ liệu (`appendfsync everysec`):**
   - *Mitigation:* `inventory_db` vẫn giữ ràng buộc `CHECK (reserved_stock <= total_stock)` nên không bao giờ bán âm tại DB nguồn; Reconciliation Job định kỳ so khớp `inv:stock:{skuId}` với `inventory_db` và tự hiệu chỉnh.
3. **Double-refund làm phình tồn kho ảo trên Redis:**
   - *Mitigation:* Thao tác hoàn kho Redis phải đi qua Lua script kiểm tra tồn tại của `inv:res:{orderId}` (xóa trước khi `INCRBY`) kết hợp cờ `redis_restored` ở bảng `stock_reservations` để đảm bảo hoàn đúng 1 lần.
