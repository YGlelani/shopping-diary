ALTER TABLE stores ADD COLUMN phone varchar(50);
ALTER TABLE stores ADD COLUMN hours_text varchar(500);
ALTER TABLE stores ADD COLUMN source_url varchar(500);

UPDATE stores s SET source_url = sources.url
FROM retailers r, (VALUES
    ('freshstop', 'directory-caledon', 'https://freshstop.co.za/store/caledon/'),
    ('flm', 'directory-garden-route-mall', 'https://foodloversmarket.co.za/stores/south-western-districts/food-lovers-market-garden-route-mall/'),
    ('flm', 'directory-harvest-place', 'https://foodloversmarket.co.za/stores/gauteng/food-lovers-market-harvest-place/')
) sources(slug, code, url)
WHERE r.slug = sources.slug AND s.retailer_id = r.id AND s.store_code = sources.code;

CREATE INDEX idx_branch_latest_product_observation
    ON price_observations (store_id, product_id, observed_at DESC, id DESC)
    INCLUDE (source_type);
