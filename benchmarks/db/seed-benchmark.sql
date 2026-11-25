
-- ============================================================
-- FlashOrder - Benchmark Database Reset & Seeding
-- PostgreSQL 16+
--
-- Purpose:
--   1. Clean previous benchmark data.
--   2. Reset inventory stock.
--   3. Seed isolated SKU datasets for 6 scenarios.
--
-- SKU groups:
--   Warm-up  : 10 SKU  x 1,000,000 stock
--   Baseline : 10,000 SKU x 10,000 stock
--   Hotspot  : 100 SKU x 10,000 stock
--   Extreme  : 1 SKU x 500 stock
--   Sustained: 100 SKU x 100,000 stock
-- ============================================================

BEGIN;

-- ============================================================
-- STEP 1: Clean previous benchmark data
-- ============================================================

-- Remove all previous reservations and orders.
-- Safe because benchmark database is completely isolated.
TRUNCATE TABLE stock_reservations, orders;

-- ============================================================
-- STEP 2: Generate and seed benchmark SKU datasets
-- ============================================================

WITH seed_groups (prefix, pad_width, sku_count, stock, label) AS (
    VALUES
        ('SKU-BENCH-WARMUP-', 2,    10, 1000000, 'Warm-up SKU'),
        ('SKU-BASE-',         5, 10000,   10000, 'Baseline SKU'),
        ('SKU-HOT100-',       3,   100,   10000, 'Hotspot SKU'),
        ('SKU-EXTREME-',      3,     1,     500, 'Extreme SKU'),
        ('SKU-SUSTAIN-',      3,   100,  100000, 'Sustained SKU')
),
generated_skus AS (
    SELECT
        g.prefix || LPAD(i::TEXT, g.pad_width, '0') AS sku_id,
        g.label || ' #' || LPAD(i::TEXT, g.pad_width, '0') AS name,
        g.stock AS total_stock
    FROM seed_groups g
    CROSS JOIN LATERAL GENERATE_SERIES(1, g.sku_count) AS series(i)
)
INSERT INTO inventory_items (
    sku_id,
    name,
    total_stock,
    reserved_stock,
    version
)
SELECT
    sku_id,
    name,
    total_stock,
    0,
    0
FROM generated_skus
ON CONFLICT (sku_id)
DO UPDATE SET
    name = EXCLUDED.name,
    total_stock = EXCLUDED.total_stock,
    reserved_stock = 0,
    version = 0,
    updated_at = CURRENT_TIMESTAMP;

-- ============================================================
-- STEP 3: Commit and refresh PostgreSQL statistics
-- ============================================================

COMMIT;

VACUUM ANALYZE inventory_items;

-- ============================================================
-- STEP 4: Verify benchmark datasets
-- ============================================================

SELECT
    CASE
        WHEN sku_id LIKE 'SKU-BENCH-WARMUP-%' THEN 'Warm-up'
        WHEN sku_id LIKE 'SKU-BASE-%'         THEN 'Baseline'
        WHEN sku_id LIKE 'SKU-HOT100-%'       THEN 'Hotspot'
        WHEN sku_id LIKE 'SKU-EXTREME-%'      THEN 'Extreme'
        WHEN sku_id LIKE 'SKU-SUSTAIN-%'      THEN 'Sustained'
    END AS sku_group,

    COUNT(*) AS sku_count,
    MIN(sku_id) AS first_sku,
    MAX(sku_id) AS last_sku,
    SUM(total_stock) AS total_stock,
    SUM(reserved_stock) AS reserved_stock,
    MAX(version) AS max_version

FROM inventory_items
WHERE sku_id LIKE 'SKU-BENCH-WARMUP-%'
   OR sku_id LIKE 'SKU-BASE-%'
   OR sku_id LIKE 'SKU-HOT100-%'
   OR sku_id LIKE 'SKU-EXTREME-%'
   OR sku_id LIKE 'SKU-SUSTAIN-%'

GROUP BY 1
ORDER BY 1;
