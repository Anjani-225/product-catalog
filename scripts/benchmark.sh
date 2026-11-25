#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "$script_dir/.." && pwd)
cd "$project_dir"

if ! command -v ab >/dev/null 2>&1; then
  echo "ApacheBench (ab) is required for the HTTP portion of this benchmark." >&2
  exit 1
fi

echo "Loading the isolated 300,000-product dataset (existing products are removed)..."
docker compose exec -T postgres psql -X -U catalog -d product_catalog < scripts/load-test-data.sql

echo "Collecting PostgreSQL execution plans..."
docker compose exec -T postgres psql -X -U catalog -d product_catalog < scripts/explain.sql

echo "Running the 30-second database read workload..."
docker compose cp scripts/read-heavy.sql postgres:/tmp/read-heavy.sql
docker compose exec -T postgres pgbench -n -U catalog -d product_catalog \
  -c 16 -j 4 -T 30 -P 5 -M prepared -f /tmp/read-heavy.sql

echo "Running the 10,000-request HTTP workload..."
ab -n 10000 -c 20 -s 30 \
  'http://127.0.0.1:8080/api/v1/products?category=Category-07&minPrice=250&maxPrice=275&sort=price&direction=asc&size=20'
