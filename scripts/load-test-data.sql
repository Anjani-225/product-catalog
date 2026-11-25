\set ON_ERROR_STOP on
\timing on

TRUNCATE TABLE products RESTART IDENTITY;

INSERT INTO products
    (sku, name, description, category, price, currency, created_at, updated_at, version)
SELECT
    'PERF-' || lpad(n::text, 8, '0'),
    'Product ' || lpad(n::text, 8, '0'),
    'Deterministic performance fixture ' || n,
    'Category-' || lpad((n % 20)::text, 2, '0'),
    ((n * 37) % 100000)::numeric / 100,
    'USD',
    timestamptz '2026-01-01 00:00:00+00' + (n || ' seconds')::interval,
    timestamptz '2026-01-01 00:00:00+00' + (n || ' seconds')::interval,
    0
FROM generate_series(1, 300000) AS n;

ANALYZE products;

SELECT count(*) AS loaded_products, min(id) AS minimum_id, max(id) AS maximum_id
FROM products;
