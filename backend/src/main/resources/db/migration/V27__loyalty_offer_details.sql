ALTER TABLE loyalty_latest_prices
    ADD COLUMN provider_price_changed_at timestamptz,
    ADD COLUMN unit_price_amount numeric(16,6) CHECK(unit_price_amount > 0),
    ADD COLUMN unit_price_measurement varchar(20),
    ADD COLUMN provider_on_promotion boolean;

ALTER TABLE loyalty_generic_types
    ADD COLUMN department_filter varchar(70),
    ADD COLUMN retailer_filter varchar(70),
    ADD COLUMN region_filter varchar(70);
