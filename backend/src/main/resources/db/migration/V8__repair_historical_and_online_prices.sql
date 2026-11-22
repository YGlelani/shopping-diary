
-- ==========================================================
-- SHOPPING DIARY
-- V8 - Repair historical and online price data
--
-- Does not delete products, prices, or retailers.
-- Review existing records and back up the database first.
-- ==========================================================

-- 1. Historical data cannot establish current stock.

UPDATE price_observations
SET
    stock_status = 'UNKNOWN',
    verification_status = 'UNVERIFIED'
WHERE source_reference = 'WFP-Historical'
  AND (
      stock_status <> 'UNKNOWN'
      OR verification_status <> 'UNVERIFIED'
  );

-- 2. Historical market records are not physical
-- supermarket branches.

UPDATE stores
SET active = FALSE
WHERE retailer_id IN (
    SELECT id
    FROM retailers
    WHERE slug = 'wfp'
)
AND active = TRUE;

-- 3. Historical WFP retailer must not appear in
-- live nearby supermarket searches.

UPDATE retailers
SET active = FALSE
WHERE slug = 'wfp'
  AND active = TRUE;

-- 4. Online-only price points must not be mapped
-- to arbitrary physical store coordinates.

UPDATE stores
SET active = FALSE
WHERE store_code = 'ONLINE'
  AND active = TRUE;

-- 5. Newest-observation query support.

CREATE INDEX IF NOT EXISTS
    idx_price_observations_latest
ON price_observations (
    product_id,
    store_id,
    observed_at DESC,
    id DESC
);

-- 6. Active alert lookup.

CREATE INDEX IF NOT EXISTS
    idx_price_alerts_active_product
ON price_alerts (product_id)
WHERE active = TRUE;

-- 7. Promotion eligibility lookup.

CREATE INDEX IF NOT EXISTS
    idx_promotions_active_product_store
ON promotions (
    product_id,
    store_id,
    start_date,
    end_date
)
WHERE status = 'ACTIVE';

-- 8. Validate basic values for future writes.

ALTER TABLE shopping_list_items
    ADD CONSTRAINT chk_sd_list_quantity_positive
    CHECK (quantity > 0)
    NOT VALID;

ALTER TABLE price_alerts
    ADD CONSTRAINT chk_sd_alert_price_positive
    CHECK (target_price > 0)
    NOT VALID;

ALTER TABLE promotions
    ADD CONSTRAINT chk_sd_promotion_dates_valid
    CHECK (end_date >= start_date)
    NOT VALID;

ALTER TABLE price_observations
    ADD CONSTRAINT chk_sd_shelf_price_positive
    CHECK (
        shelf_price IS NULL
        OR shelf_price > 0
    )
    NOT VALID;

ALTER TABLE price_observations
    ADD CONSTRAINT chk_sd_promo_price_positive
    CHECK (
        promo_price IS NULL
        OR promo_price > 0
    )
    NOT VALID;

-- ==========================================================
-- END V8
-- ==========================================================
