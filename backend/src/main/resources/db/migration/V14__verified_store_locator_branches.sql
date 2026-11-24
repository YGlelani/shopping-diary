-- Branch addresses and pins are taken from the retailers' store locator links.
-- Avoid registering the same physical site under a second code.
INSERT INTO stores (retailer_id, store_code, name, address, city, province, location, store_type)
SELECT r.id, seed.code, seed.name, seed.address, seed.city, seed.province, pin.location, seed.store_type
FROM (VALUES
    ('freshstop', 'directory-caledon', 'FreshStop Caledon', '1 Nerina Street', 'Caledon',
        'Western Cape', 19.4252453, -34.2313647, 'INDEPENDENT'),
    ('flm', 'directory-garden-route-mall', 'Food Lover''s Market Garden Route Mall', 'Garden Route Mall, Park Road',
        'George', 'Western Cape', 22.5007062, -33.9883836, 'SUPERMARKET'),
    ('flm', 'directory-harvest-place', 'Food Lover''s Market Harvest Place', 'Harvest Place Shopping Centre, Monument Road, Glen Marais',
        'Kempton Park', 'Gauteng', 28.2639068, -26.0678675, 'SUPERMARKET')
) AS seed(slug, code, name, address, city, province, lon, lat, store_type)
JOIN retailers r ON r.slug = seed.slug
CROSS JOIN LATERAL (SELECT ST_SetSRID(ST_MakePoint(seed.lon, seed.lat), 4326) AS location) pin
WHERE NOT EXISTS (
    SELECT 1 FROM stores existing
    WHERE existing.retailer_id = r.id AND existing.store_code IS DISTINCT FROM 'ONLINE'
      AND (lower(existing.name) = lower(seed.name)
           OR ST_DWithin(existing.location::geography, pin.location::geography, 150))
)
ON CONFLICT (retailer_id, store_code) DO NOTHING;
