CREATE INDEX idx_products_catalogue_text_trgm ON products
    USING gin (lower(canonical_name || ' ' || coalesce(brand, '')) gin_trgm_ops);

CREATE INDEX idx_products_legacy_category_trgm ON products
    USING gin (lower(btrim(category)) gin_trgm_ops)
    WHERE category_id IS NULL AND category IS NOT NULL;
