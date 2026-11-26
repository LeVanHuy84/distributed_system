-- FlashOrder Seed Data: Inventory Items
-- Phục vụ cho các kịch bản kiểm thử đa luồng và benchmark hiệu năng (Phase 1)

INSERT INTO inventory_items (sku_id, name, total_stock, reserved_stock, version)
VALUES 
    -- 1. Nhóm sản phẩm Flash Sale / Extreme Contention (1.000 VUs tranh chấp 1 SKU duy nhất, Task 7.1)
    ('SKU-HOT-IPHONE15-100', 'iPhone 15 Pro Max 256GB Titan Tự Nhiên (Flash Sale)', 100, 0, 0),
    ('SKU-HOT-SONY-PS5-50', 'PlayStation 5 Slim Digital Edition (Hot Deal)', 50, 0, 0),

    -- 2. Nhóm sản phẩm Medium Contention / Hotspot (100-500 VUs trên nhiều SKU)
    ('SKU-IPHONE15-128-BLK', 'iPhone 15 128GB Đen Nguyên Bản', 100, 0, 0),
    ('SKU-IPHONE15-256-BLU', 'iPhone 15 256GB Xanh Dương', 80, 0, 0),
    ('SKU-MACBOOK-M3-16G', 'MacBook Air M3 16GB/512GB Space Gray', 60, 0, 0),
    ('SKU-AIRPODS-PRO-2', 'AirPods Pro Gen 2 USB-C MagSafe', 200, 0, 0),
    ('SKU-SAMSUNG-S24U-512', 'Samsung Galaxy S24 Ultra 512GB Titanium Gray', 50, 0, 0),
    ('SKU-KEYBOARD-KEYCHRON-K2', 'Bàn phím cơ Keychron K2 Pro Wireless', 150, 0, 0),
    ('SKU-MOUSE-LOGITECH-MX3S', 'Chuột không dây Logitech MX Master 3S', 120, 0, 0),
    ('SKU-MONITOR-DELL-U2723QE', 'Màn hình Dell UltraSharp 27 Inch 4K U2723QE', 40, 0, 0),

    -- 3. Nhóm sản phẩm Baseline Benchmark (Đo Throughput trần 100 VUs, không tranh chấp, Task 7.1)
    ('SKU-BENCH-BASELINE-10K-A', 'Sản phẩm Benchmark Baseline Alpha (10.000 tồn)', 10000, 0, 0),
    ('SKU-BENCH-BASELINE-10K-B', 'Sản phẩm Benchmark Baseline Beta (10.000 tồn)', 10000, 0, 0),
    ('SKU-BENCH-MASSIVE-50K', 'Sản phẩm Benchmark Sustained Load (50.000 tồn)', 50000, 0, 0)
ON CONFLICT (sku_id) DO NOTHING;
