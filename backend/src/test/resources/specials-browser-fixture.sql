DO $guard$
BEGIN
    IF current_database() <> 'discovery_test' THEN
        RAISE EXCEPTION 'The specials demonstration requires the disposable discovery_test database';
    END IF;
END
$guard$;

WITH item AS (
    INSERT INTO products(canonical_name,brand,barcode,size_text,category)
    VALUES ('Fixture Full Cream Milk 2 L','Fixture Dairy','0098765643216','2 L','Dairy and eggs')
    ON CONFLICT (barcode) DO UPDATE SET barcode=excluded.barcode RETURNING id
)
INSERT INTO price_observations(product_id,store_id,shelf_price,source_type,stock_status,verification_status,content_hash)
SELECT item.id,s.id,24,'MANUAL','IN_STOCK','VERIFIED','phase-seven-fixture-shelf'
FROM item CROSS JOIN stores s WHERE s.store_code='directory-farmyard-polokwane'
ON CONFLICT (content_hash) DO NOTHING;

INSERT INTO promotions(product_id,store_id,promo_type,title,description,promo_price,min_quantity,start_date,end_date,
                       status,loyalty_program,source_type,source_reference,source_key,verification)
SELECT p.id,s.id,d.kind,d.title,d.terms,d.price,d.quantity,CURRENT_DATE-1,CURRENT_DATE+d.days,
       'ACTIVE',d.program,'FIXTURE','Isolated browser demonstration',d.key,'VERIFIED'
FROM products p CROSS JOIN stores s CROSS JOIN (
    VALUES ('MULTIBUY','Fixture milk multibuy','Buy two identical 2 L cartons for R42.',42,2,2,NULL,'phase-seven-multibuy'),
           ('MEMBER','Fixture milk member price','Fixture loyalty card is required.',18,1,3,'Fixture loyalty card','phase-seven-member')
) d(kind,title,terms,price,quantity,days,program,key)
WHERE p.barcode='0098765643216' AND s.store_code='directory-farmyard-polokwane'
ON CONFLICT (source_type,source_key) DO NOTHING;
