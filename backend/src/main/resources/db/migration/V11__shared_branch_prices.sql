CREATE VIEW current_branch_promotions AS
SELECT p.*
FROM promotions p
WHERE p.status = 'ACTIVE'
  AND (CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Johannesburg')::date BETWEEN p.start_date AND p.end_date
  AND p.promo_price > 0
  AND (p.promo_type <> 'MULTIBUY' OR p.min_quantity > 1);

CREATE FUNCTION current_branch_price(product bigint, branch bigint, max_age_days integer)
RETURNS TABLE (
    shelf_price numeric, promo_price numeric, member_price numeric, unit_price numeric,
    observed_at timestamptz, verification_status varchar, stock_status varchar,
    source_type varchar, effective_price numeric
)
LANGUAGE sql STABLE
AS $$
    SELECT CASE WHEN latest.shelf_price > 0 THEN latest.shelf_price END,
           CASE WHEN discount.price > 0 OR latest.promo_price > 0 THEN
               LEAST(CASE WHEN latest.promo_price > 0 THEN latest.promo_price END, discount.price,
                     CASE WHEN latest.shelf_price > 0 THEN latest.shelf_price END)
           END,
           CASE WHEN latest.member_price > 0 THEN latest.member_price END,
           latest.unit_price, latest.observed_at, latest.verification_status, latest.stock_status,
           latest.source_type,
           LEAST(CASE WHEN latest.shelf_price > 0 THEN latest.shelf_price END,
                 CASE WHEN latest.promo_price > 0 THEN latest.promo_price END, discount.price)
    FROM (
        SELECT po.*
        FROM price_observations po
        WHERE po.product_id = product AND po.store_id = branch AND po.observed_at <= CURRENT_TIMESTAMP
        ORDER BY po.observed_at DESC, po.id DESC LIMIT 1
    ) latest
    LEFT JOIN LATERAL (
        SELECT min(promo_price) AS price
        FROM current_branch_promotions p
        WHERE p.product_id = product AND p.store_id = branch
          AND p.promo_type = 'DISCOUNT' AND coalesce(p.min_quantity, 1) = 1
    ) discount ON TRUE
    WHERE latest.stock_status = 'IN_STOCK'
      AND latest.verification_status IN ('VERIFIED', 'UNVERIFIED')
      AND latest.observed_at >= CURRENT_TIMESTAMP - make_interval(days =>
          least(max_age_days, CASE latest.source_type WHEN 'COMMUNITY' THEN 3 WHEN 'RECEIPT' THEN 3
                              WHEN 'BROCHURE' THEN 3 ELSE max_age_days END))
      AND (latest.shelf_price > 0 OR latest.promo_price > 0)
$$;
