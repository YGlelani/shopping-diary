DO $guard$
BEGIN
    IF current_database() <> 'discovery_test' THEN
        RAISE EXCEPTION 'The shopping-list browser fixture requires the disposable discovery_test database';
    END IF;
END
$guard$;

WITH demo AS (
    INSERT INTO products (canonical_name, brand, barcode, size_text, category)
    VALUES ('Verification Milk 2 L', 'Demo Dairy', '0098765643210', '2 L', 'Dairy and eggs')
    ON CONFLICT (barcode) DO UPDATE SET barcode = products.barcode
    RETURNING id
)
INSERT INTO price_observations (product_id, store_id, shelf_price, source_type, stock_status,
                                verification_status, content_hash)
SELECT demo.id, s.id, 24, 'MANUAL', 'IN_STOCK', 'VERIFIED', 'phase-four-browser-fixture'
FROM demo CROSS JOIN stores s WHERE s.store_code = 'directory-farmyard-polokwane'
ON CONFLICT (content_hash) DO NOTHING;
