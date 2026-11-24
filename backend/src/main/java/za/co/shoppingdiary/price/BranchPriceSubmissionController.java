package za.co.shoppingdiary.price;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.auth.UserPrincipal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BranchPriceSubmissionController {
    private final JdbcClient jdbc;

    @PostMapping("/price-submissions")
    public void submit(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody Submission input) {
        int inserted = jdbc.sql("""
            INSERT INTO branch_price_submissions (user_id, product_id, store_id, shelf_price, observed_at, evidence)
            SELECT :userId, p.id, s.id, :price, :observedAt, :evidence
            FROM products p CROSS JOIN stores s JOIN retailers r ON r.id = s.retailer_id
            WHERE p.id = :productId AND s.id = :storeId AND s.active AND r.active
              AND s.store_code IS DISTINCT FROM 'ONLINE'
              AND :observedAt BETWEEN CURRENT_TIMESTAMP - interval '3 days' AND CURRENT_TIMESTAMP
            ON CONFLICT (user_id, product_id, store_id) WHERE status = 'PENDING' DO NOTHING
            """).param("productId", input.productId()).param("storeId", input.storeId())
                .param("price", input.price()).param("observedAt", input.observedAt())
                .param("evidence", input.evidence()).param("userId", user.getId()).update();
        if (inserted == 0) throw new IllegalArgumentException(
                "Use an active branch and a recent observation. Only one pending report per product and branch is allowed.");
    }

    @GetMapping("/admin/price-submissions")
    public List<Pending> pending() {
        return jdbc.sql("""
            SELECT q.id, p.canonical_name AS product, p.brand, p.barcode, p.size_text,
                s.name AS branch, r.name AS retailer, q.shelf_price, q.observed_at, q.evidence
            FROM branch_price_submissions q JOIN products p ON p.id = q.product_id
            JOIN stores s ON s.id = q.store_id JOIN retailers r ON r.id = s.retailer_id
            WHERE q.status = 'PENDING' ORDER BY q.id LIMIT 100
            """).query(Pending.class).list();
    }

    @PostMapping("/admin/price-submissions/{id}")
    @Transactional
    public void review(@PathVariable long id, @RequestParam boolean approve) {
        int updated = jdbc.sql("""
            UPDATE branch_price_submissions SET status = :status WHERE id = :id AND status = 'PENDING'
            """).param("status", approve ? "APPROVED" : "REJECTED").param("id", id).update();
        if (updated == 0) throw new IllegalArgumentException("Submission is no longer pending");
        if (approve) {
            jdbc.sql("""
                INSERT INTO price_observations (product_id, store_id, source_type, source_reference,
                    observed_at, shelf_price, stock_status, verification_status, content_hash)
                SELECT q.product_id, q.store_id, 'COMMUNITY', 'Price submission ' || q.id, q.observed_at,
                    q.shelf_price, 'IN_STOCK', 'VERIFIED', 'community-submission-' || q.id
                FROM branch_price_submissions q WHERE id = :id
                """).param("id", id).update();
        }
    }

    public record Submission(@Positive long productId, @Positive long storeId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
            @NotNull OffsetDateTime observedAt, @AssertTrue boolean inStock, @Size(max = 500) String evidence) {}
    public record Pending(long id, String product, String brand, String barcode, String sizeText, String branch,
                          String retailer, BigDecimal shelfPrice, OffsetDateTime observedAt, String evidence) {}
}
