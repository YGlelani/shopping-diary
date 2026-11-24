package za.co.shoppingdiary.ingestion.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/scoped-prices")
@RequiredArgsConstructor
public class ScopedPriceController {
    private final JdbcClient jdbc;
    private final LoyaltyHubSource loyalty;

    public record Offer(String source,String scope,String scopeLabel,String retailer,String branchName,BigDecimal amount,
                        BigDecimal regularAmount,BigDecimal promotionalAmount,Boolean inStock,
                        OffsetDateTime observedAt,LocalDate observedOn,boolean historical,String sourceUrl) {}

    @GetMapping("/{productId}")
    public List<Offer> prices(@PathVariable long productId) {
        return jdbc.sql("""
            SELECT offer.source,offer.scope,offer.scope_label,offer.retailer,s.name AS branch_name,
                   offer.amount,offer.regular_amount,offer.promotional_amount,offer.in_stock,
                   offer.observed_at,offer.observed_on,
                   (offer.observed_at IS NULL OR offer.observed_at<now()-interval '24 hours') AS historical,
                   offer.source_url
            FROM (
                SELECT DISTINCT ON (p.source,p.scope,p.scope_label,p.retailer_id,p.branch_id)
                    p.*,r.name AS retailer
                FROM grocery_scoped_prices p LEFT JOIN retailers r ON r.id=p.retailer_id
                WHERE p.product_id=:product AND
                    ((p.source='LOYALTYHUB' AND :loyalty AND p.retailer_id IS NOT NULL AND p.scope IN ('REGIONAL','ONLINE','CATALOGUE')
                         AND p.observed_at>=now()-interval '7 days' AND p.collected_at>=now()-interval '30 days')
                      OR (p.source='OPEN_PRICES' AND p.scope='BRANCH' AND p.branch_id IS NOT NULL))
                ORDER BY p.source,p.scope,p.scope_label,p.retailer_id,p.branch_id,
                         p.observed_at DESC NULLS LAST,p.observed_on DESC NULLS LAST,p.id DESC
            ) offer LEFT JOIN stores s ON s.id=offer.branch_id
            ORDER BY offer.observed_at DESC NULLS LAST,offer.observed_on DESC NULLS LAST LIMIT 20
            """).param("product",productId).param("loyalty",loyalty.enabled()).query(Offer.class).list();
    }
}
