# Performance verification

## Scope and method

These are local measurements, not capacity guarantees. They establish that the schema, indexes, query shapes and application behave sensibly at the assessment's stated scale. The run was performed on 2026-11-25 with Docker Desktop 29.9.3, PostgreSQL 17.7, the Compose application image running Temurin Java 17.0.21, and a macOS host with 8 GB assigned to Docker.

The deterministic fixture in [`scripts/load-test-data.sql`](../scripts/load-test-data.sql) truncates the local `products` table, inserts exactly 300,000 products across 20 evenly distributed categories, assigns deterministic prices from USD 0.00 through 999.99, and runs `ANALYZE`. Loading took 1.723 seconds and `ANALYZE` took 76 ms in the measured run. This fixture is isolated from automated tests and must never be run against a database containing valuable data.

Run the complete procedure against a healthy Compose stack:

```bash
./scripts/benchmark.sh
```

The runner loads the fixture, executes [`scripts/explain.sql`](../scripts/explain.sql), runs a 30-second prepared-statement `pgbench` workload from [`scripts/read-heavy.sql`](../scripts/read-heavy.sql), and then runs an HTTP benchmark. ApacheBench (`ab`) must be installed for the HTTP phase.

## Query plans

The figures below are warm-cache observations from `EXPLAIN (ANALYZE, BUFFERS)` after the final index set was installed.

- SKU lookup used `uk_products_sku`: 0.033 ms execution and 4 shared-buffer hits.
- Category page ordered by `id` used the primary key, stopped after finding 20 matches, and took 0.058 ms with 12 shared-buffer hits. With evenly distributed categories this was cheaper than a separate `(category, id)` index, so that redundant index was removed.
- Price range ordered by `(price, id)` used `idx_products_price_id`: 0.163 ms and 23 shared-buffer hits.
- Combined category and price range ordered by `(price, id)` used `idx_products_category_price_id`: 0.062 ms and 23 shared-buffer hits.
- A deep category page at offset 10,000 used a bitmap scan on `idx_products_category_price_id`, then an external merge sort. It took 12.312 ms, touched 5,893 shared buffers and used about 2 MB of temporary disk. This demonstrates the cost growth inherent in offset pagination.

The category-leading composite index serves category-only filtering through its leftmost prefix as well as combined category/price filtering. The final schema therefore retains only the SKU unique index, the primary key, `(price, id)`, and `(category, price, id)`.

## Database read workload

Command:

```bash
docker compose exec -T postgres pgbench -n -U catalog -d product_catalog \
  -c 16 -j 4 -T 30 -P 5 -M prepared -f /tmp/read-heavy.sql
```

Each pgbench transaction executes four reads: primary-key lookup, category page, price page, and combined category/price page. Results:

- 714,983 transactions in 30 seconds
- 23,838.21 transactions/second, excluding initial connection time
- 0.666 ms average transaction latency; 0.503 ms standard deviation
- 0 failed transactions
- Four SELECTs per transaction, approximately 95,353 SELECT statements/second in this local, warm-cache database-only test

After the workload, `pg_stat_user_indexes` recorded 1,429,975 primary-key scans, 722,143 price-index scans and 714,991 combined-index scans. This confirms that the benchmark exercised the intended indexes.

## HTTP read workload

The API workload requested a 20-item combined category/price page, including the count query needed for pagination metadata:

```bash
ab -n 10000 -c 20 -s 30 \
  'http://127.0.0.1:8080/api/v1/products?category=Category-07&minPrice=250&maxPrice=275&sort=price&direction=asc&size=20'
```

Results:

- 10,000 completed requests and 0 failures
- 2,900.99 requests/second
- 6.894 ms mean end-to-end latency
- 5 ms median, 15 ms p95, 25 ms p99 and 78 ms maximum
- Response size was 5,238 bytes; transfer rate was about 14.8 MiB/s

## Limitations

- Results are from one laptop, one application container and one database container; they are not production sizing data.
- Data is uniform and synthetic. Real category skew, text sizes, concurrent writes, autovacuum, network latency and cold storage will change plans and latency.
- The database workload is warm-cache and bypasses HTTP, JSON serialization and the connection pool. The separate HTTP workload covers those layers but uses a single endpoint and local loopback networking.
- ApacheBench opens simple HTTP/1.0 connections and is not a model of real client traffic.
- Offset pagination remains intentionally supported by the API contract. For routinely deep navigation, use a cursor based on the active sort key plus `id`.
- Re-run `EXPLAIN (ANALYZE, BUFFERS)` with production-like data before changing indexes or capacity.
