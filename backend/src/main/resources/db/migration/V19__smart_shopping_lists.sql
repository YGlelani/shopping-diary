ALTER TABLE shopping_lists ADD COLUMN description varchar(1000);
ALTER TABLE shopping_lists ADD COLUMN revision bigint NOT NULL DEFAULT 0;
ALTER TABLE shopping_lists ADD COLUMN location_label varchar(150);
ALTER TABLE shopping_lists ADD COLUMN location_lat double precision;
ALTER TABLE shopping_lists ADD COLUMN location_lon double precision;
ALTER TABLE shopping_lists ADD CONSTRAINT chk_list_location CHECK (
    (location_lat IS NULL AND location_lon IS NULL)
    OR (location_lat IS NOT NULL AND location_lon IS NOT NULL AND location_lat BETWEEN -90 AND 90 AND location_lon BETWEEN -180 AND 180)
);
ALTER TABLE shopping_list_items ADD COLUMN notes varchar(1000);
ALTER TABLE shopping_list_items ADD COLUMN purchased boolean NOT NULL DEFAULT false;
ALTER TABLE shopping_list_items ADD COLUMN substitute_product_id bigint REFERENCES products(id) ON DELETE SET NULL;

-- Keep existing preferences and work intact. Backfill the display name for future catalogue deletions.
UPDATE shopping_list_items li SET free_text = p.canonical_name
FROM products p WHERE li.product_id = p.id AND li.free_text IS NULL;

CREATE TABLE shopping_list_add_requests (
    list_id bigint NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    item_id bigint REFERENCES shopping_list_items(id) ON DELETE SET NULL,
    PRIMARY KEY (list_id, request_id)
);

CREATE FUNCTION shopping_list_item_offer(item shopping_list_items, branch bigint, max_age_days integer)
RETURNS TABLE (product_id bigint, product_name varchar, total numeric)
LANGUAGE sql STABLE
AS $$
    SELECT p.id, p.canonical_name, round(least(item.quantity * price.effective_price,
        bundle.amount), 2) AS total
    FROM products p
    CROSS JOIN LATERAL current_branch_price(p.id, branch, max_age_days) price
    LEFT JOIN LATERAL (
        SELECT min(offer.promo_price * trunc(item.quantity / offer.min_quantity)
                    + price.effective_price * mod(item.quantity, offer.min_quantity)) AS amount
        FROM current_branch_promotions offer
        WHERE offer.product_id = p.id AND offer.store_id = branch AND offer.promo_type = 'MULTIBUY'
    ) bundle ON TRUE
    WHERE (p.id = item.product_id OR (item.flexible_brand AND p.id = item.substitute_product_id))
      AND (item.unit IS NULL OR item.unit IN ('item','pack','bottle','carton','bag'))
      AND (item.flexible_brand OR item.preferred_brand IS NULL OR lower(p.brand) = lower(item.preferred_brand))
    ORDER BY total, (lower(p.brand) = lower(item.preferred_brand)) DESC NULLS LAST,
             (p.id = item.product_id) DESC, p.id
    LIMIT 1
$$;
