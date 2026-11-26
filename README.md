# FlashOrder

Hệ thống xử lý đặt hàng và giữ chỗ tồn kho chịu tải cao (**Flash Sale Inventory & Order Engine**), được xây dựng để nghiên cứu, triển khai và đo lường thực nghiệm các bài toán **Concurrency Control, Database Locking, Connection Pool Contention** và **Distributed Reliability** từ nguyên lý gốc.

---

## Kiến trúc & Điểm nhấn Kỹ thuật (Phase 1)

- **Kiến trúc Modular Monolith ([ADR-0001](docs/adr/0001-modular-monolith-architecture.md)):**
  - Phân tách rõ ranh giới giữa các module `order`, `inventory`, `mock` và `common`.
  - Giao tiếp qua service boundary với DTO bất biến (Java 25 Records), sẵn sàng tách thành Microservices + Redis/Kafka ở Phase 2.
- **Thuần Spring JDBC & Native SQL ([ADR-0002](docs/adr/0002-spring-jdbc-over-jpa-for-concurrency-control.md)):**
  - Sử dụng `JdbcTemplate` + `RowMapper` thay vì JPA/Hibernate để kiểm soát hoàn toàn SQL, thời lượng giữ DB connection và row-level lock trên PostgreSQL 16.
  - Quản lý schema chặt chẽ qua Flyway ([V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql)).
- **3 Chiến lược Concurrency có thể chuyển đổi động qua Header `X-Concurrency-Strategy`:**
  1. `OPTIMISTIC`: Kiểm tra `version` (`UPDATE ... WHERE sku_id = ? AND version = ?`) kết hợp retry + exponential backoff.
  2. `PESSIMISTIC`: Khóa dòng tường minh bằng `SELECT ... FOR UPDATE` kèm `SET LOCAL lock_timeout`.
  3. `ATOMIC_UPDATE` *(mặc định)*: Trừ tồn kho nguyên tử trong 1 câu SQL (`UPDATE ... WHERE sku_id = ? AND (total_stock - reserved_stock) >= ?`).
- **Bảo toàn dữ liệu & Tự thu hồi giữ chỗ (Zero Overselling & TTL Worker):**
  - Ràng buộc cứng ở tầng DB Engine: `CHECK (reserved_stock <= total_stock)` và `UNIQUE (order_id, sku_id)`.
  - Background worker định kỳ giải phóng các reservation quá hạn (`RESERVED` $\rightarrow$ `RELEASED`) và xử lý an toàn tranh chấp với luồng xác nhận thanh toán.

---

## Tech Stack

| Nhóm | Công nghệ |
| :--- | :--- |
| **Language & Framework** | Java 25, Spring Boot 4.1.2, Embedded Tomcat 11, Virtual Threads |
| **Database & Migration** | PostgreSQL 16 (`postgres:16-alpine`), Spring Data JDBC (`JdbcTemplate`), Flyway, HikariCP |
| **Observability & Quality** | Spring Boot Actuator, Micrometer Prometheus, Spotless (Palantir Java Format) |
| **Testing & Benchmark** | JUnit 5, Testcontainers (PostgreSQL), `grafana/k6` (Docker), Taskfile, GitHub Actions CI |

---

## Tóm tắt Kết quả Benchmark (Phase 1)

Chạy bằng `grafana/k6` trên PostgreSQL 16 cô lập (`hikari.maximum-pool-size: 20`). Chi tiết đầy đủ tại [Báo cáo Benchmark Phase 1](docs/learning/benchmark-phase-1.md).

| Kịch bản | Chiến lược nổi bật | RPS | `p95` Latency | Ghi chú |
| :--- | :--- | ---: | ---: | :--- |
| **01. Baseline** *(100 VUs, 10k SKU, 60s)* | `ATOMIC_UPDATE` | **1,368.25** | 148.87 ms | `0` lỗi hệ thống (`82,224` đơn `201`) |
| **04. Hotspot High** *(1.000 VUs, 100 SKU, 30s)* | `ATOMIC_UPDATE` (`virtual`) | **1,323.49** | 1,786.47 ms | `PESSIMISTIC` (`virtual`) lỗi `4,644` HikariCP timeout; `platform` p95 phẳng hơn (`906.67 ms`) |
| **05. Extreme Contention** *(1.000 VUs, 1 SKU, 500 stock)* | `ATOMIC_UPDATE` (`virtual`) | **1,562.97** | 1,671.39 ms | **6/6 lượt** đều tạo đúng `500/500` đơn (`0` overselling) |
| **06. Sustained Load** *(200 VUs, 100 SKU, 5m)* | `ATOMIC_UPDATE` | **1,337.21** | 345.14 ms | `401,274` đơn liên tục trong 5 phút, `0` lỗi |

> **Insight chính:** Khi bottleneck nằm ở DB lock và HikariCP Pool (`20` connections), bật `Virtual Threads` không làm tăng RPS trần mà còn khiến `p95/p99` cao hơn do thiếu Bulkhead giới hạn concurrency trước tầng DB (trong khi `Platform Threads` tự giới hạn ở 200 worker threads).

---

## Cấu trúc Thư mục

```text
.
├── .github/workflows/ci.yml        # GitHub Actions CI (Spotless + Testcontainers tests)
├── benchmarks/
│   ├── db/seed-benchmark.sql       # Script reset & seed dữ liệu SKU benchmark
│   └── k6/                         # Warm-up, module dùng chung (lib/) và 6 kịch bản tải
├── docs/
│   ├── adr/                        # Architecture Decision Records (ADR-0001, ADR-0002)
│   ├── learning/                   # Báo cáo benchmark & tài liệu phân tích hiệu năng
│   └── tasks-phase-1.md            # Kế hoạch & tiến độ triển khai Phase 1
├── src/main/
│   ├── java/com/flashorder/        # Các module: common, inventory, order, mock
│   └── resources/
│       ├── application.yaml        # Cấu hình mặc định
│       ├── application-benchmark.yaml # Profile cô lập cho benchmark (port 5433 + Actuator)
│       └── db/migration/           # Flyway migrations (V1 schema, V2 seed)
├── docker-compose.yml              # PostgreSQL cho local dev (port 5432)
├── docker-compose.bench.yml        # PostgreSQL cô lập cho benchmark (port 5433)
├── Taskfile.yml                    # Tự động hóa build, test, db, và benchmark pipeline
└── build.gradle.kts                # Gradle build & Spotless configuration
```

---

## Hướng dẫn Chạy nhanh (Quick Start)

### Yêu cầu môi trường
- **Java 25** (Gradle Wrapper đã tích hợp sẵn)
- **Docker & Docker Compose**
- **Task (`go-task`)** *(khuyến nghị, hoặc chạy trực tiếp qua `./gradlew`)*

### 1. Chạy ứng dụng Local Dev

```bash
# Bật PostgreSQL dev (localhost:5432)
task db:up

# Khởi động Spring Boot (Flyway tự migrate V1 & V2)
task dev
```

Gửi thử request đặt hàng với chiến lược `ATOMIC_UPDATE` (hoặc `OPTIMISTIC` / `PESSIMISTIC`):

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "X-Concurrency-Strategy: ATOMIC_UPDATE" \
  -d '{
    "userId": "user-001",
    "skuId": "SKU-HOT-IPHONE15-100",
    "quantity": 1,
    "unitPrice": 100.00
  }'
```

### 2. Chạy Test & Kiểm tra Format

```bash
# Kiểm tra format code (Spotless)
task format:check

# Chạy toàn bộ tests (bao gồm Testcontainers PostgreSQL & Concurrent tests)
task test
```

### 3. Chạy Bộ Benchmark (k6 Docker)

```bash
# Terminal 1: Bật PostgreSQL benchmark cô lập (port 5433) và app profile benchmark
task bench:db:up
task bench:app

# Terminal 2: Chạy toàn bộ 6 kịch bản x 3 strategies và kiểm chứng bất biến DB
task bench:suite
```

*(Để đo với Platform Threads thay cho Virtual Threads: `task bench:app VIRTUAL_THREADS=false` và `task bench:matrix SCENARIO=04-hotspot-high THREADS_MODE=platform`.)*

---

## API Endpoints chính

| Method | Endpoint | Mô tả |
| :--- | :--- | :--- |
| `POST` | `/api/v1/orders` | Tạo đơn hàng & giữ chỗ tồn kho (hỗ trợ header `X-Concurrency-Strategy`) |
| `GET` | `/api/v1/orders/{id}` | Tra cứu thông tin & trạng thái đơn hàng |
| `POST` | `/api/v1/orders/{id}/pay` | Giả lập thanh toán & xác nhận xuất kho (`CONFIRMED`) |

---

## Tài liệu Tham khảo

- [Kế hoạch & Tiến độ Phase 1](docs/tasks-phase-1.md)
- [ADR 0001: Kiến trúc Modular Monolith](docs/adr/0001-modular-monolith-architecture.md)
- [ADR 0002: Spring Data JDBC / `JdbcTemplate` thay cho JPA](docs/adr/0002-spring-jdbc-over-jpa-for-concurrency-control.md)
- [Báo cáo Benchmark & Phân tích Hiệu năng Phase 1](docs/learning/benchmark-phase-1.md)
