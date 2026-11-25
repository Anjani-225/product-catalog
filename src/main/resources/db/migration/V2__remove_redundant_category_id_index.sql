-- Representative plans showed this index was not selected. The
-- (category, price, id) index covers the category-only leftmost prefix.
DROP INDEX IF EXISTS idx_products_category_id;
