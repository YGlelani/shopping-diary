package za.co.shoppingdiary.ingestion.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ScopedPriceController {
    private final JdbcClient jdbc;
    private final LoyaltyHubSource loyalty;

    public record Offer(String source,String scope,String scopeLabel,String retailer,String branchName,BigDecimal amount,
                        BigDecimal regularAmount,BigDecimal promotionalAmount,BigDecimal membershipAmount,
                        String promotionTerms,Boolean loyaltyRequired,Boolean inStock,
                        OffsetDateTime observedAt,LocalDate observedOn,boolean historical,String sourceUrl,
                        BigDecimal unitPriceAmount,String unitPriceMeasurement,OffsetDateTime providerPriceChangedAt) {}

    public record Details(String description,String manufacturer,String department,String variant,String flavour) {}

    @GetMapping("/products/catalogue-details/{productId}")
    public Details details(@PathVariable long productId) {
        return jdbc.sql("""
            SELECT attributes->>'description' AS description,attributes->>'manufacturer' AS manufacturer,
                attributes->>'department' AS department,attributes->>'variant' AS variant,attributes->>'flavour' AS flavour
            FROM loyalty_product_metadata WHERE :loyalty AND product_id=:product
            """).param("product",productId).param("loyalty",loyalty.enabled()).query(Details.class)
                .optional().orElse(new Details(null,null,null,null,null));
    }

    @GetMapping("/products/catalogue-prices/{productId}")
    public List<Offer> prices(@PathVariable long productId) {
        return jdbc.sql("""
            SELECT 'LOYALTYHUB' AS source,p.scope,p.scope_label,r.name AS retailer,NULL::text AS branch_name,
                   CASE WHEN active_promotion OR p.promotional_amount IS NULL THEN p.amount ELSE p.regular_amount END AS amount,
                   p.regular_amount,
                   CASE WHEN fresh AND active_promotion THEN p.promotional_amount END AS promotional_amount,
                   CASE WHEN fresh AND active_promotion THEN p.membership_amount END AS membership_amount,
                   CASE WHEN fresh AND active_promotion THEN p.promotion_terms END AS promotion_terms,
                   CASE WHEN fresh AND active_promotion THEN p.loyalty_required END AS loyalty_required,
                   p.in_stock,p.observed_at,NULL::date AS observed_on,NOT fresh AS historical,p.source_url,
                   CASE WHEN active_promotion OR p.promotional_amount IS NULL THEN p.unit_price_amount END AS unit_price_amount,
                   p.unit_price_measurement,p.provider_price_changed_at
            FROM loyalty_latest_prices p JOIN retailers r ON r.id=p.retailer_id
            CROSS JOIN LATERAL (SELECT observed_at>=now()-interval '24 hours' AS fresh,
                (promotion_expires_at IS NULL OR promotion_expires_at>now()) AS active_promotion) status
            WHERE :loyalty AND p.product_id=:product AND r.active AND p.observed_at>=now()-interval '7 days'
              AND (active_promotion OR p.promotional_amount IS NULL OR p.regular_amount IS NOT NULL)
              AND p.collected_at>=now()-interval '30 days'
            ORDER BY observed_at DESC,retailer,scope_label LIMIT 30
            """).param("product",productId).param("loyalty",loyalty.enabled()).query(Offer.class).list();
    }

    @GetMapping("/scoped-prices/{productId}")
    public List<Offer> scopedPrices(@PathVariable long productId) {
        var offers=new java.util.ArrayList<>(prices(productId));
        offers.addAll(jdbc.sql("""
            SELECT DISTINCT ON (p.branch_id,p.retailer_id)
                p.source,p.scope,p.scope_label,r.name AS retailer,s.name AS branch_name,
                p.amount,p.regular_amount,p.promotional_amount,p.membership_amount,p.promotion_terms,p.loyalty_required,
                p.in_stock,p.observed_at,p.observed_on,
                (p.observed_at IS NULL OR p.observed_at<now()-interval '24 hours') AS historical,p.source_url, NULL::numeric AS unit_price_amount, NULL::text AS unit_price_measurement, NULL::timestamptz AS provider_price_changed_at
            FROM grocery_scoped_prices p JOIN stores s ON s.id=p.branch_id
            LEFT JOIN retailers r ON r.id=p.retailer_id
            WHERE p.product_id=:product AND p.source='OPEN_PRICES' AND p.scope='BRANCH'
            ORDER BY p.branch_id,p.retailer_id,p.observed_at DESC NULLS LAST,p.observed_on DESC NULLS LAST,p.id DESC LIMIT 20
            """).param("product",productId).query(Offer.class).list());
        return offers;
    }
}
