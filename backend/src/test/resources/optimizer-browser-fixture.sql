DO $guard$
BEGIN
    IF current_database() <> 'discovery_test' THEN
        RAISE EXCEPTION 'The optimiser demonstration requires the disposable discovery_test database';
    END IF;
END
$guard$;

INSERT INTO stores(retailer_id, name, store_code, address, location, city, province)
SELECT r.id, fixture.name, fixture.code, 'Synthetic test branch',
       ST_SetSRID(ST_MakePoint(fixture.lon,-23.90),4326), 'Polokwane', 'Limpopo'
FROM (VALUES ('checkers','Fixture North','phase-six-demo-north',29.455),
             ('shoprite','Fixture South','phase-six-demo-south',29.475)) fixture(slug,name,code,lon)
JOIN retailers r ON r.slug=fixture.slug
WHERE NOT EXISTS (SELECT 1 FROM stores s WHERE s.store_code=fixture.code);

INSERT INTO products(canonical_name,brand,barcode,size_text,category)
SELECT 'PhaseSix Pantry ' || lpad(item::text,2,'0'),CASE WHEN item=11 THEN 'Fixture Alternative' ELSE 'Fixture Brand' END,
       '99000000000' || lpad(item::text,2,'0'),'1 kg','Pantry essentials'
FROM generate_series(1,11) item
ON CONFLICT (barcode) DO NOTHING;

INSERT INTO price_observations(product_id,store_id,shelf_price,source_type,stock_status,verification_status,content_hash)
SELECT p.id,s.id,CASE WHEN right(p.barcode,2)='11' THEN 40
                     WHEN right(p.barcode,2)::int<=5 THEN CASE WHEN s.store_code='phase-six-demo-north' THEN 60 ELSE 80 END
                     ELSE CASE WHEN s.store_code='phase-six-demo-south' THEN 45 ELSE 75 END END,
       'MANUAL','IN_STOCK','VERIFIED','phase-six-demo-' || p.barcode || '-' || s.store_code
FROM products p CROSS JOIN stores s
WHERE p.barcode BETWEEN '9900000000001' AND '9900000000011'
  AND s.store_code IN ('phase-six-demo-north','phase-six-demo-south')
ON CONFLICT (content_hash) DO NOTHING;
