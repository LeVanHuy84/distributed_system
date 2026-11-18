# FlashOrder - Product & Engineering Vision

## 1. Executive Summary & Định vị Dự án
**FlashOrder** là dự án Backend chuyên sâu về **High-Performance Concurrency & Distributed Systems** được xây dựng trên nền tảng **Java 25 (Spring Boot)**. 

Dự án mô phỏng bài toán kinh điển nhưng khốc liệt nhất của các hệ thống thương mại điện tử / đặt vé / flash-sale quy mô lớn: **10.000+ người dùng đồng thời tranh chấp 100 sản phẩm trong vài giây.**

### Mục tiêu cốt lõi:
1. **Kiểm soát tuyệt đối tính toàn vẹn dữ liệu (Data Consistency):** Tuyệt đối không bán âm kho (zero overselling), không duplicate đơn hàng khi retry, không thất thoát event khi worker crash.
2. **Thực nghiệm & Đo đạc định lượng (Empirical Benchmark):** Không phỏng đoán hiệu năng dựa trên cảm tính. Mọi quyết định kỹ thuật từ Concurrency Locking, Virtual Threads, Connection Pool đến Saga Pattern đều có số liệu benchmark bằng k6 và profiling tái lập được để chứng minh trade-offs.
3. **Vũ khí phỏng vấn chuyên sâu (Interview-Driven Engineering):** Được thiết kế để khi interviewer đào sâu 30–45 phút vào Concurrency, Database Engine, Transaction Isolation, Distributed Transactions, bạn có đầy đủ bằng chứng, sơ đồ luồng và bài học thực tế để tự tin bảo vệ giải pháp.
4. **Văn hóa Code tay 100% (Zero-Boilerplate-Blindness):** Không để AI generate mã nguồn hàng loạt. Lập trình viên tự tay viết từng dòng code nghiệp vụ và truy vấn SQL (sử dụng Spring JDBC + JdbcTemplate thay vì phụ thuộc hoàn toàn vào ORM), AI đóng vai trò **Engineering Mentor** và **Independent Reviewer**.

---

## 2. Technical Storyline & Điểm nhấn phỏng vấn

Dự án xây dựng câu chuyện kỹ thuật xuyên suốt 2 tầng kiến trúc:

```
[Phase 1: Local Concurrency & DB Engine]
               │
               ▼
   Hotspot Contention & Isolation
   Optimistic vs Pessimistic vs Atomic Update
   Virtual Threads (Loom) vs HikariCP Pool Sizing
               │
               ▼
[Phase 2: Distributed Reliability & Eventual Consistency]
               │
               ▼
   Modular Monolith ──(Splitting Boundary)──► Microservices
   Transactional Outbox Pattern (Zero Event Loss)
   Idempotent Kafka Consumer (Exactly-Once Semantics in Business)
   Saga Pattern (Compensating Transactions on Payment Failure)
               │
               ▼
[Phase 3: Production Engineering & Observability]
   Redis Pre-allocation & Rate Limiting
   Prometheus, Grafana, OpenTelemetry Tracing
```

### 4 Chủ đề "sát thương cao" trong phỏng vấn được FlashOrder chứng minh:
- **Database Engine & Locking:** Đi sâu vào PostgreSQL MVCC, row-level locks (`SELECT FOR UPDATE`), deadlock detection, snapshot isolation, lost updates, và vì sao `Atomic SQL Update` vượt trội trong kịch bản high-contention.
- **Virtual Threads (Project Loom) vs Platform Threads:** Tác động của Virtual Threads trên Java 25 khi tương tác với JDBC blocking calls, HikariCP connection pool pinning/carrier thread contention.
- **Distributed Reliability:** Tại sao Dual-Write (ghi DB rồi gửi Kafka) luôn thất bại trong thực tế? Cách Transactional Outbox giải quyết triệt để lỗi mạng và cách hiện thực Idempotency Key bảo vệ hệ thống trước retry storms.
- **Distributed Saga:** Thiết kế bồi hoàn giao dịch (Compensating Transactions) khi thanh toán thất bại, xử lý các trạng thái chập chờn (partial failures), phân tích trade-off giữa Orchestration và Choreography.

---

## 3. Tech Stack Chiến lược

| Thành phần | Lựa chọn | Lý do kỹ thuật & Giá trị phỏng vấn |
| :--- | :--- | :--- |
| **Language** | **Java 25** | Khai thác các tính năng mới nhất của Java hiện đại: Virtual Threads (Loom), Pattern Matching, Record Patterns. |
| **Framework** | **Spring Boot 3.x** | Framework tiêu chuẩn công nghiệp nhưng được cấu hình tinh gọn, kiểm soát chặt chẽ Bean lifecycle. |
| **Persistence** | **Spring Data JDBC / `JdbcTemplate`** | **Không dùng Hibernate/JPA cho luồng hotspot**. Viết trực tiếp SQL để kiểm soát 100% câu lệnh locking, query plan, connection checkout và tránh ma thuật ORM (N+1, dirty checking overhead). |
| **Database** | **PostgreSQL** | RDBMS mạnh mẽ với MVCC, row-level locking tinh vi, CTEs, và transaction isolation levels chuẩn mực. |
| **Connection Pool** | **HikariCP** | Nghiên cứu và tinh chỉnh pool size (`maximumPoolSize`, `minimumIdle`, `connectionTimeout`) để tìm điểm bão hòa tối ưu. |
| **Database Migration**| **Flyway** | Version control schema database nghiêm ngặt, đảm bảo migration có thể rollback và kiểm thử tự động. |
| **Testing** | **JUnit 5 + Testcontainers** | Chạy integration test trên container PostgreSQL thực tế, không dùng in-memory H2 (vì H2 không phản ánh đúng cơ chế locking của Postgres). |
| **Load Testing** | **k6** | Viết kịch bản kiểm thử tải dạng code JS, đo lường chi tiết Latency (p95, p99), Throughput (RPS), Error Rate, HTTP vs DB execution time. |
| **Messaging (Phase 2)**| **Apache Kafka** | Nền tảng event-driven xử lý event stream, consumer group rebalance, idempotency, partition key routing. |
| **Cache (Phase 3)** | **Redis** | Redis string atomic ops / Lua scripts cho rate limiting và pre-decrement stock. |

---

## 4. Lộ trình phát triển 3 Giai đoạn (Evolutionary Architecture)

### 🔹 Phase 1: Core Concurrency & Database Engine (Modular Monolith)
- **Kiến trúc:** Modular Monolith (domain `inventory` và `order` trong cùng một Spring Boot application, phân chia package và interface rõ ràng).
- **Trọng tâm:** 
  - Mô hình Stock Reservation (Available Stock vs Reserved Stock với TTL).
  - Nghiên cứu, cài đặt và benchmark so sánh 3 chiến lược concurrency: **Optimistic Locking**, **Pessimistic Locking**, và **Atomic SQL Update**.
  - Kiểm thử đồng thời (Concurrent Tests) bằng Testcontainers.
  - So sánh Virtual Threads vs Platform Threads khi chịu tải tranh chấp.

### 🔹 Phase 2: Distributed Reliability & Eventual Consistency (Tách Service + Kafka)
- **Kiến trúc:** Tách `Order Service` và `Inventory Service` thành 2 microservices độc lập.
- **Trọng tâm:**
  - Transactional Outbox Pattern với Polling Publisher / CDC.
  - Idempotent Kafka Consumer ngăn chặn duplicate delivery.
  - Saga Pattern xử lý vòng đời đơn hàng: Create Order -> Reserve Stock -> Process Payment -> Confirm / Rollback.
  - Xử lý timeout và Dead Letter Queue (DLQ).

### 🔹 Phase 3: High-Throughput Optimization & Observability
- **Trọng tâm:**
  - Redis Caching & Lua Scripting để giảm tải trực tiếp cho PostgreSQL.
  - Distributed Rate Limiter bảo vệ API Gateway.
  - Full Observability stack: Metrics (Prometheus + Grafana), Tracing (OpenTelemetry), Logs (Loki).
  - JVM Memory & GC Profiling khi chịu tải cực hạn.

---

## 5. Nguyên tắc Kỹ thuật (Engineering Principles)
1. **Evidence-Based Engineering:** Không kết luận một giải pháp là "nhanh hơn" hoặc "tốt hơn" nếu không có số liệu benchmark và profile chứng minh.
2. **Zero Auto-Code:** Mọi mã nguồn nghiệp vụ phải được lập trình viên hiểu và tự viết. AI cung cấp kiến thức nền tảng, phản biện thiết kế và review tìm lỗi.
3. **Fail-Safe & Idempotent by Default:** Mọi endpoint và consumer đều phải giả định mạng chập chờn, request có thể retry nhiều lần và service có thể crash bất kỳ lúc nào.
4. **Document Every Decision:** Mọi quyết định thay đổi cấu trúc đều phải có Architecture Decision Record (ADR) trong thư mục `docs/adr/`.
