\set product_id random(1, 300000)
\set category_id random(0, 19)
\set low_cents random(0, 95000)

SELECT id, sku, name, category, price, currency, version
FROM products WHERE id = :product_id;

SELECT id, sku, name, category, price, currency, version
FROM products
WHERE category = 'Category-' || lpad(:category_id::text, 2, '0')
ORDER BY id
LIMIT 20;

SELECT id, sku, name, category, price, currency, version
FROM products
WHERE price BETWEEN (:low_cents::numeric / 100) AND ((:low_cents + 2500)::numeric / 100)
ORDER BY price, id
LIMIT 20;

SELECT id, sku, name, category, price, currency, version
FROM products
WHERE category = 'Category-' || lpad(:category_id::text, 2, '0')
  AND price BETWEEN (:low_cents::numeric / 100) AND ((:low_cents + 2500)::numeric / 100)
ORDER BY price, id
LIMIT 20;
