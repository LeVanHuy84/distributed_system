# Báo cáo Benchmark Hiệu năng & Phân tích Concurrency — Phase 1

- **Dự án:** FlashOrder (Modular Monolith — Core Concurrency & Database Engine)
- **Ngày thực hiện:** 2026-11-25
- **Dữ liệu nguồn:** [summary-all.json](../../benchmarks/results/summary-all.json) (24 kịch bản đo bằng `grafana/k6`)

---

## 1. Tóm tắt Điều hành (Executive Summary)

1. **Zero Overselling được chứng minh 100%:** Ở cả **6 lượt chạy** kịch bản *Extreme Contention* (`1.000 VUs` tranh chấp `1 SKU` có `total_stock = 500`), toàn bộ 3 chiến lược (`ATOMIC_UPDATE`, `OPTIMISTIC`, `PESSIMISTIC`) trên cả `Virtual Threads` và `Platform Threads` đều chỉ cho phép tạo **đúng `500` đơn `201 Created`**, `0` lỗi client `4xx`, và từ chối hợp lệ phần còn lại bằng `409 Conflict`.
2. **`ATOMIC_UPDATE` là chiến lược cân bằng và ổn định nhất ở Phase 1:** Dẫn đầu về throughput ở hầu hết kịch bản (`1,368 RPS` Baseline, `1,323 RPS` ở 1.000 VUs Hotspot High, `1,563 RPS` ở Extreme Contention, `1,337 RPS` ổn định suốt 5 phút với hơn `401.274` đơn thành công và `0` lỗi hệ thống).
3. **`PESSIMISTIC` (`SELECT ... FOR UPDATE`) có chi phí cao nhất dưới tải lớn:** Chậm hơn `ATOMIC_UPDATE` từ **18%–25%** ở tải thường và tụt xuống **`557–800 RPS`** ở Extreme Contention do lock serialization và cần nhiều DB round-trip hơn.
4. **Bài học cốt lõi về Virtual Threads (Java 25):** Virtual Threads **không tự động tăng tốc workload bị nghẽn bởi DB Lock và HikariCP Pool nhỏ (`20` connections)**. Ngược lại, khi không có lớp điều tiết concurrency (bulkhead), việc cho phép `1.000` Virtual Threads cùng lao vào pool `20` connections khiến tail latency (`p95/p99`) tăng gần gấp đôi và gây `4,644` lỗi `HikariCP timeout` ở `PESSIMISTIC` — trong khi giới hạn `200` worker threads mặc định của `Platform Threads` vô tình đóng vai trò như một **admission control** giúp latency phẳng hơn rõ rệt.

---

## 2. Môi trường & Cấu hình Đo lường

| Thành phần | Thông số thực tế |
| :--- | :--- |
| **CPU** | 12th Gen Intel(R) Core(TM) i5-1240P (12 Cores / 16 Logical Processors) |
| **RAM** | 15.71 GB |
| **Thiết bị** | Acer Swift SF314-512 (Windows Host) |
| **Java / Framework** | Java 25, Spring Boot 4.1.2, Embedded Apache Tomcat 11.0.27, Spring JDBC (`JdbcTemplate`) |
| **Database** | PostgreSQL 16.16 (`postgres:16-alpine` trong Docker, port `5433`, volume cô lập) |
| **HikariCP Pool** | `maximum-pool-size: 20`, `minimum-idle: 10`, `connection-timeout: 5000ms` |
| **Tomcat Benchmark Config** | `max-keep-alive-requests: -1`, `accept-count: 1024`, `keep-alive-timeout: 60s` |
| **Load Generator** | `grafana/k6` chạy qua Docker container (`host.docker.internal:8080`) |
| **Quy trình mỗi lượt đo** | `TRUNCATE orders, stock_reservations` + Seed lại tồn kho + `VACUUM ANALYZE` $\rightarrow$ Warm-up `10s` (20 VUs) $\rightarrow$ Chạy kịch bản chính |

> **Lưu ý về độ chính xác môi trường:** Cả `k6` và `postgres-bench` cùng chạy trong Docker Desktop (WSL2), giao tiếp với Spring Boot trên Windows Host qua `host.docker.internal`. Mọi kịch bản được đo trên cùng một cấu hình cố định để đảm bảo tính so sánh công bằng tương đối.

---

## 3. Thực nghiệm A: So sánh 3 Concurrency Strategies (Virtual Threads)

### 3.1. Bảng tổng hợp số liệu

| Kịch bản | Chiến lược | Total Reqs | RPS | `201 OK` | `409 Conflict` | `SysErr` | `p50` (ms) | `p95` (ms) | `p99` (ms) | `Max` (ms) |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **01. Baseline**<br>*(100 VUs, 10k SKU, 60s)* | `ATOMIC_UPDATE` | 82,224 | **1,368.25** | 82,224 | 0 | 0 | 66.49 | **148.87** | **205.05** | 497.79 |
| | `OPTIMISTIC` | 81,165 | 1,351.72 | 81,165 | 0 | 0 | **61.89** | 162.14 | 245.62 | 599.90 |
| | `PESSIMISTIC` | 67,045 | 1,116.56 | 67,045 | 0 | 0 | 74.10 | 203.84 | 303.13 | 692.98 |
| **02. Hotspot Low**<br>*(100 VUs, 100 SKU, 30s)* | `ATOMIC_UPDATE` | 41,907 | **1,394.56** | 41,907 | 0 | 0 | **64.16** | **144.23** | **198.00** | 373.37 |
| | `OPTIMISTIC` | 31,497 | 1,046.51 | 31,447 | 50 | 0 | 81.98 | 199.98 | 272.52 | 543.27 |
| | `PESSIMISTIC` | 31,514 | 1,048.34 | 31,514 | 0 | 0 | 86.77 | 194.68 | 267.59 | 643.33 |
| **03. Hotspot Medium**<br>*(500 VUs, 100 SKU, 30s)* | `ATOMIC_UPDATE` | 40,085 | **1,318.74** | 40,085 | 0 | 0 | **290.37** | 912.54 | 1,369.92 | 2,865.56 |
| | `OPTIMISTIC` | 39,150 | 1,293.74 | 39,105 | 45 | 0 | 346.83 | **869.78** | **1,195.94** | 2,143.32 |
| | `PESSIMISTIC` | 32,969 | 1,082.45 | 32,969 | 0 | 0 | 352.50 | 1,103.08 | 1,641.24 | 3,196.73 |
| **04. Hotspot High**<br>*(1.000 VUs, 100 SKU, 30s)* | `ATOMIC_UPDATE` | 40,384 | **1,323.49** | 40,384 | 0 | 0 | **579.47** | **1,786.47** | **2,619.71** | 4,580.14 |
| | `OPTIMISTIC` | 34,677 | 1,122.31 | 34,636 | 41 | 0 | 654.76 | 2,133.23 | 2,954.26 | 5,465.27 |
| | `PESSIMISTIC` | 35,222 | 1,143.12 | 30,578 | 0 | **4,644** | 676.93 | 2,197.97 | 3,463.63 | 7,604.84 |
| **05. Extreme Contention**<br>*(1.000 VUs, 1 SKU, 500 stock, 10s)* | `ATOMIC_UPDATE` | 16,232 | **1,562.97** | **500** | 15,732 | 0 | 515.73 | **1,671.39** | **2,304.07** | 3,887.67 |
| | `OPTIMISTIC` | 9,809 | 954.06 | **500** | 9,309 | 0 | **435.17** | 4,070.63 | 5,341.83 | 6,740.91 |
| | `PESSIMISTIC` | 6,372 | 557.39 | **500** | 5,872 | 0 | 1,482.59 | 3,044.26 | 3,306.14 | 4,678.66 |
| **06. Sustained Load**<br>*(200 VUs, 100 SKU, 5m)* | `ATOMIC_UPDATE` | 401,274 | **1,337.21** | 401,274 | 0 | 0 | **119.47** | **345.14** | **506.67** | 1,289.87 |
| | `OPTIMISTIC` | 347,097 | 1,156.36 | 346,664 | 433 | 0 | 146.28 | 394.18 | 568.59 | 1,524.65 |
| | `PESSIMISTIC` | 317,468 | 1,057.81 | 317,468 | 0 | 0 | 164.48 | 426.60 | 609.24 | 1,717.93 |

### 3.2. Phân tách Latency nhánh Thành công (`201`) vs Từ chối/Conflict (`409`)

Dữ liệu từ `order_success_duration` và `order_conflict_duration` giúp nhìn rõ chi phí nội tại của từng cơ chế ở các kịch bản có `409`:

| Kịch bản | Chiến lược | `201` p95 (ms) | `201` p99 (ms) | `409` p95 (ms) | `409` p99 (ms) | Nhận xét |
| :--- | :--- | ---: | ---: | ---: | ---: | :--- |
| **02. Hotspot Low** | `OPTIMISTIC` | 199.22 | 271.73 | 312.47 | 374.16 | Nhánh `409` chậm hơn `201` ~100ms do phải đi hết vòng retry + backoff mới throw `ConcurrencyConflictException`. |
| **03. Hotspot Medium** | `OPTIMISTIC` | 868.21 | 1,193.32 | 1,379.11 | 1,501.40 | Tương tự: request hết lượt retry chịu tail latency cao hơn hẳn request thành công. |
| **05. Extreme Contention** | `ATOMIC_UPDATE` | 2,339.38 | 2,760.81 | **1,092.17** | **2,258.94** | 500 đơn đầu thành công cần `UPDATE` + `INSERT reservation` + `INSERT order`; sau khi hết hàng, `409` trả về nhanh hơn gấp đôi! |
| | `OPTIMISTIC` | 5,348.32 | 6,217.47 | 3,985.18 | 5,185.46 | Retry storm ở 500 đơn đầu làm p99 thành công vọt lên **6.2 giây**! |
| | `PESSIMISTIC` | 3,289.33 | 4,178.44 | 2,881.43 | 3,269.62 | Cả `201` và `409` đều phải xếp hàng qua `SELECT ... FOR UPDATE` nên latency chậm đều (~3–4s). |

### 3.3. Giải thích nguyên nhân gốc rễ (Root-Cause Analysis)

1. **Vì sao `ATOMIC_UPDATE` vượt trội ở mọi kịch bản?**
   - Trong [InventoryJdbcRepository.java](../../src/main/java/com/flashorder/inventory/repository/InventoryJdbcRepository.java#L158-L168), `ATOMIC_UPDATE` gộp việc kiểm tra điều kiện và cộng tồn kho giữ chỗ thành **1 câu SQL duy nhất**:
     `UPDATE inventory_items SET reserved_stock = reserved_stock + ? ... WHERE sku_id = ? AND (total_stock - reserved_stock) >= ?`.
   - PostgreSQL chỉ giữ row lock trong đúng thời gian thực thi lệnh `UPDATE` đơn lẻ đó (không mất thêm round-trip đọc trước như `OPTIMISTIC` hay `SELECT FOR UPDATE` + `UPDATE` như `PESSIMISTIC`), giảm tối đa thời lượng giữ DB connection trong HikariCP.
   - Khi hết hàng (`05-extreme-contention`), lệnh `UPDATE` trả về `0 rows affected` ngay lập tức, giúp xả tải cực nhanh.

2. **Vì sao `PESSIMISTIC` thua ngay cả ở `01-baseline` (không tranh chấp)?**
   - Mặc dù `01-baseline` có `10.000 SKU` nên hầu như không đụng row lock nhau, `PESSIMISTIC` vẫn chỉ đạt `1,116.56 RPS` (so với `1,368.25 RPS` của `ATOMIC_UPDATE`) vì mỗi transaction phải thực hiện tuần tự **3 câu SQL** ([InventoryJdbcRepository.java](../../src/main/java/com/flashorder/inventory/repository/InventoryJdbcRepository.java#L131-L155)): `SET LOCAL lock_timeout` $\rightarrow$ `SELECT ... FOR UPDATE` $\rightarrow$ `UPDATE`. Chi phí network round-trip tăng lên làm mỗi thread giữ 1 connection trong `20` slots lâu hơn $\rightarrow$ giảm throughput tổng.
   - Khi lên `04-hotspot-high` (`1.000 VUs` trên `Virtual Threads`), hàng chờ vào 20 HikariCP connections bị kéo dài quá `connection-timeout: 5000ms` (latency max đạt `7,604.84ms`), gây ra **`4,644` lỗi `500`** (tỷ lệ lỗi `13.18%`).

3. **Vì sao `OPTIMISTIC` gặp "Retry Storm" ở `05-extreme-contention`?**
   - Ở `02`, `03`, `06` (100 SKU, tồn kho dư), `OPTIMISTIC` vẫn có `41–433` request bị `409 Conflict` (dù kho còn rất nhiều hàng!) do nhiều thread đọc cùng một `version` và vượt quá số lần retry tối đa.
   - Ở `05-extreme-contention`, khi 1.000 VUs cùng đọc `version` của `SKU-EXTREME-001`, 999 thread cập nhật trượt rồi ngủ exponential backoff và thử lại, tạo ra lượng lớn truy vấn `SELECT`/`UPDATE` thừa (wasted DB work), kéo `OK201_p99` lên tới **`6,217.47ms`** và đẩy `RPS` xuống còn **`954.06`**.

4. **Độ bền dài hạn (`06-sustained-load` trong 5 phút):**
   - Trong 5 phút ghi liên tục `401,274` đơn (`ATOMIC_UPDATE`), RPS giữ ở `1,337.21` — gần như không suy giảm so với Baseline (`1,368.25`, chênh lệch `< 2.3%`). Hệ thống không có dấu hiệu memory leak, connection leak hay GC pause bất thường.

---

## 4. Thực nghiệm B: So sánh Virtual Threads vs Platform Threads (1.000 VUs)

### 4.1. Bảng đối chiếu trực tiếp ở kịch bản `04` và `05`

| Kịch bản | Chiến lược | Thread Model | RPS | `201 OK` | `409 Conflict` | `SysErr` | `p50` (ms) | `p95` (ms) | `p99` (ms) | `Max` (ms) |
| :--- | :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **04. Hotspot High**<br>*(1.000 VUs, 100 SKU)* | `ATOMIC_UPDATE` | `virtual` | **1,323.49** | 40,384 | 0 | 0 | **579.47** | 1,786.47 | 2,619.71 | 4,580.14 |
| | | `platform` | 1,316.51 | 40,336 | 0 | 0 | 729.83 | **906.67** | **1,018.00** | **1,359.72** |
| | `OPTIMISTIC` | `virtual` | 1,122.31 | 34,636 | 41 | 0 | **654.76** | 2,133.23 | 2,954.26 | 5,465.27 |
| | | `platform` | **1,142.51** | 35,112 | 48 | 4* | 850.60 | **1,103.02** | **1,230.31** | **1,547.31** |
| | `PESSIMISTIC` | `virtual` | 1,143.12 | 30,578 | 0 | **4,644** | **676.93** | 2,197.97 | 3,463.63 | 7,604.84 |
| | | `platform` | 1,073.09 | 33,110 | 0 | **0** | 898.09 | **1,101.06** | **1,219.79** | **1,717.01** |
| **05. Extreme Contention**<br>*(1.000 VUs, 1 SKU)* | `ATOMIC_UPDATE` | `virtual` | **1,562.97** | 500 | 15,732 | 0 | 515.73 | 1,671.39 | 2,304.07 | 3,887.67 |
| | | `platform` | 1,561.29 | 500 | 15,337 | 457* | **469.47** | **1,449.48** | **1,920.18** | **2,127.30** |
| | `OPTIMISTIC` | `virtual` | 954.06 | 500 | 9,309 | 0 | 435.17 | 4,070.63 | 5,341.83 | 6,740.91 |
| | | `platform` | **1,968.75** | 500 | 19,628 | 0 | **268.68** | **2,373.60** | **3,114.59** | **3,487.60** |
| | `PESSIMISTIC` | `virtual` | 557.39 | 500 | 5,872 | 0 | 1,482.59 | 3,044.26 | 3,306.14 | 4,678.66 |
| | | `platform` | **800.47** | 500 | 8,338 | 0 | **1,050.08** | **2,011.50** | **2,227.01** | **2,283.36** |

> *(\*) Ghi chú về `SysErr = 4` và `457` ở chế độ `platform`:* Đây là lỗi TCP `connect: connection refused` xảy ra duy nhất ở mili-giây `t = 0.0s` khi 1.000 VUs cùng mở kết nối TCP vượt ngưỡng `listen` backlog mặc định của Windows/Docker (~200) lúc Tomcat worker threads chưa kịp accept; không phải lỗi HikariCP hay DB timeout (max latency vẫn chỉ `1.5s–2.1s`). Ngược lại, `4,644` lỗi của `PESSIMISTIC (virtual)` có `max = 7,604.84ms > 5000ms` là lỗi quá tải HikariCP connection pool thật sự.

### 4.2. Phân tích cơ chế: Tại sao `Platform Threads` lại có `p95/p99` tốt hơn `Virtual Threads`?

Đây là phát hiện quan trọng nhất của Phase 1 — phản bác hiểu lầm phổ biến rằng *"cứ bật Virtual Threads là hệ thống tự nhanh hơn"*:

1. **Nút cổ chai nằm ở HikariCP (`20` connections) & PostgreSQL, không nằm ở Tomcat Thread:**
   - Với `ATOMIC_UPDATE` ở `04-hotspot-high`, `virtual` (`1,323 RPS`) và `platform` (`1,316 RPS`) có throughput **gần như bằng nhau** vì cả hai đều bị chặn trần bởi tốc độ xử lý của 20 kết nối PostgreSQL.
2. **Hiệu ứng "Bulkhead vô hình" của Platform Threads (`200` worker threads):**
   - Với `platform`, Tomcat chỉ cho tối đa `200` requests vào tầng Service/Transaction cùng lúc; `800` requests còn lại chờ ngay ở socket queue của Tomcat. Do đó, số thread cùng tranh giành `20` HikariCP connections tối đa chỉ là `200`, thời gian chờ pool ổn định quanh ~`900–1,200ms` (`p99 = 1,018ms`, `0` HikariCP timeout).
   - Với `virtual`, Tomcat nhận **cả 1.000 requests** vào tầng Service cùng lúc $\rightarrow$ `1.000` Virtual Threads đồng thời tranh nhau `20` DB connections! Hệ quả:
     - `p95/p99` tăng gần **gấp đôi** (`p99 = 2,619ms` vs `1,018ms` ở `ATOMIC_UPDATE`).
     - Với `PESSIMISTIC`, thời gian giữ connection lâu khiến nhiều Virtual Threads chờ quá `5,000ms` $\rightarrow$ nổ **`4,644` lỗi HikariCP timeout**.
     - Với `OPTIMISTIC` ở Extreme Contention, `1.000` threads cùng tranh version gây retry storm nặng hơn nhiều so với `200` threads $\rightarrow$ RPS tụt một nửa (`954` vs `1,968 RPS`).
3. **Khi nào Virtual Threads mới thực sự tỏa sáng?**
   - Khi hệ thống có **tác vụ I/O mạng ngoài DB** (như gọi Payment Gateway bên thứ 3 mất `50–200ms`, gửi notification, gọi gRPC sang service khác) **trước hoặc ngoài transaction DB**, lúc đó 200 Platform Threads sẽ bị nghẽn hết còn Virtual Threads dễ dàng gánh hàng nghìn kết nối đồng thời với mức RAM cực thấp.

---

## 5. Kết luận & Kiến nghị cho Phase 2

1. **Chốt chiến lược Phase 1:** `ATOMIC_UPDATE` là lựa chọn tối ưu cho luồng trừ kho trên PostgreSQL đơn lẻ (ít round-trip nhất, không retry storm, miễn nhiễm deadlock, ổn định `>1,300 RPS` suốt 5 phút).
2. **Kiến nghị kiến trúc nếu tiếp tục dùng Virtual Threads ở Phase 1.5 / Phase 2:**
   - **Bắt buộc áp dụng Bulkhead / Concurrency Limiter (Semaphore / Resilience4j)** trước khi vào tầng DB transaction (thay vì để hàng nghìn Virtual Threads xếp hàng vô kiểm soát trước HikariCP).
   - **Tinh chỉnh HikariCP & Retry:** Không giữ connection trong lúc chờ backoff của `OPTIMISTIC`; cấu hình `connection-timeout` và `lock_timeout` đồng bộ để fail-fast thay vì treo 5 giây.
3. **Giới hạn trần của PostgreSQL đơn lẻ (Động lực sang Phase 2):**
   - Ở mức `1.000 VUs` tranh chấp `1 SKU`, ngay cả `ATOMIC_UPDATE` tốt nhất cũng bị giới hạn bởi single-row lock contention trên đĩa/WAL của PostgreSQL (`~1,563 RPS`, `p99 ~ 2.3s`).
   - Để đạt mục tiêu Flash Sale `10.000+ VUs` với latency `< 50ms`, **Phase 2** cần đưa lớp giữ chỗ nóng lên **Redis Atomic Lua Script / Distributed Rate Limiter** ở phía trước và đẩy việc ghi nhận đơn hàng xuống **Message Queue (Kafka / Outbox Pattern)** theo hướng xử lý bất đồng bộ.
