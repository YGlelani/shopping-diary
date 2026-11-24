package za.co.shoppingdiary.store.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import za.co.shoppingdiary.store.LocalityDirectory;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
public class StoreController {
    private final JdbcClient jdbc;
    private final LocalityDirectory localities;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxAgeDays;

    private static final String BRANCH = """
        SELECT s.id, s.name, r.name AS retailer, r.slug AS retailer_slug, r.logo_url,
               s.address, s.city, s.province, s.postal_code, s.phone, s.hours_text, s.source_url,
               ST_Y(s.location) AS lat, ST_X(s.location) AS lon,
               ST_Distance(s.location::geography, origin.point) / 1000 AS distance_km,
               coverage.recorded_products, coverage.eligible_products, coverage.verified_products,
               coverage.recent_products, coverage.latest_observed, coverage.latest_eligible,
               selected.effective_price AS product_price
        FROM stores s JOIN retailers r ON r.id = s.retailer_id
        CROSS JOIN origin
        %s
        LEFT JOIN LATERAL (
            SELECT count(*) AS recorded_products, count(eligible.effective_price) AS eligible_products,
                   count(*) FILTER (WHERE eligible.verification_status = 'VERIFIED') AS verified_products,
                   count(*) FILTER (WHERE latest.observed_at >= CURRENT_TIMESTAMP - make_interval(days =>
                       least(:days, CASE latest.source_type WHEN 'COMMUNITY' THEN 3 WHEN 'RECEIPT' THEN 3
                                     WHEN 'BROCHURE' THEN 3 ELSE :days END)))
                       AS recent_products,
                   max(latest.observed_at) AS latest_observed, max(eligible.observed_at) AS latest_eligible
            FROM (
                SELECT DISTINCT ON (product_id) product_id, observed_at, source_type
                FROM price_observations
                WHERE store_id = s.id AND observed_at <= CURRENT_TIMESTAMP
                ORDER BY product_id, observed_at DESC, id DESC
            ) latest
            LEFT JOIN LATERAL current_branch_price(latest.product_id, s.id, :days) eligible ON TRUE
        ) coverage ON TRUE
        LEFT JOIN LATERAL current_branch_price(:productId, s.id, :days) selected ON :productId IS NOT NULL
        WHERE s.active AND r.active AND upper(s.store_code) IS DISTINCT FROM 'ONLINE'
        """;

    private static final String ORIGIN = """
        WITH origin AS (
            SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS point
        )
        """;

    @GetMapping("/nearby")
    public List<NearbyStore> nearby(@RequestParam double lat, @RequestParam double lon,
            @RequestParam(defaultValue = "15") double r, @RequestParam(required = false) String retailer,
            @RequestParam(required = false) String area, @RequestParam(required = false) Boolean priced,
            @RequestParam(required = false) Boolean promotions, @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "distance") String sort,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        validate(lat, lon, r, offset, limit);
        if (productId != null && productId < 1) throw new IllegalArgumentException("Invalid product");
        String order = switch (sort) {
            case "distance" -> "distance_km";
            case "freshness" -> "latest_observed DESC NULLS LAST";
            case "name" -> "s.name";
            case "price" -> {
                if (productId == null) throw new IllegalArgumentException("Select a product to sort by price");
                yield "product_price";
            }
            default -> throw new IllegalArgumentException("Invalid sorting option");
        };
        String radiusFilter = """
              AND ST_DWithin(s.location::geography, origin.point, :radius)
              AND (CAST(:retailer AS text) IS NULL OR r.slug = :retailer)
              AND (CAST(:area AS text) IS NULL OR strpos(lower(concat_ws(' ', s.city, s.province)), lower(:area)) > 0)
            """;
        // For orders that depend only on store metadata, page the spatial candidates before counting prices.
        boolean prePage = productId == null && priced == null && !Boolean.TRUE.equals(promotions)
                && ("distance".equals(sort) || "name".equals(sort));
        String sql;
        if (prePage) {
            String candidateOrder = "distance".equals(sort) ?
                    "ST_Distance(s.location::geography, origin.point)" : "s.name";
            sql = ORIGIN + """
                , candidates AS MATERIALIZED (
                    SELECT s.id FROM stores s JOIN retailers r ON r.id = s.retailer_id CROSS JOIN origin
                    WHERE s.active AND r.active AND upper(s.store_code) IS DISTINCT FROM 'ONLINE'
                """ + radiusFilter + " ORDER BY " + candidateOrder + ", s.id LIMIT :limit OFFSET :offset) "
                    + BRANCH.formatted("JOIN candidates ON candidates.id = s.id") + " ORDER BY " + order + ", s.id";
        } else {
            sql = ORIGIN + BRANCH.formatted("") + radiusFilter + """
                  AND (CAST(:priced AS boolean) IS NULL OR (coverage.eligible_products > 0) = :priced)
                  AND (:productId IS NULL OR selected.effective_price IS NOT NULL)
                  AND (CAST(:promotions AS boolean) IS DISTINCT FROM TRUE OR EXISTS (
                      SELECT 1 FROM current_branch_promotions offer
                      CROSS JOIN LATERAL current_branch_price(offer.product_id, s.id, :days)
                      WHERE offer.store_id = s.id AND (:productId IS NULL OR offer.product_id = :productId)
                  ))
                ORDER BY %s, s.id LIMIT :limit OFFSET :offset
                """.formatted(order);
        }
        return jdbc.sql(sql).paramSource(parameters(lat, lon, productId)
                .addValue("radius", r * 1000).addValue("retailer", filter(retailer), Types.VARCHAR)
                .addValue("area", filter(area), Types.VARCHAR).addValue("priced", priced, Types.BOOLEAN)
                .addValue("promotions", promotions, Types.BOOLEAN)
                .addValue("limit", limit).addValue("offset", offset)).query(NearbyStore.class).list();
    }

    @GetMapping("/{id}")
    public NearbyStore branch(@PathVariable long id, @RequestParam(required = false) Double lat,
                              @RequestParam(required = false) Double lon) {
        validate(lat, lon, 15, 0, 1);
        return jdbc.sql(ORIGIN + BRANCH.formatted("") + " AND s.id = :id")
                .paramSource(parameters(lat, lon, null).addValue("id", id)).query(NearbyStore.class)
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
    }

    @GetMapping("/{id}/offers")
    public List<BranchOffer> offers(@PathVariable long id, @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "25") int limit) {
        validate(null, null, 15, offset, limit);
        return jdbc.sql("""
            SELECT p.id AS product_id, p.canonical_name, p.brand, p.size_text, p.image_url, p.barcode, price.shelf_price,
                   price.effective_price, price.member_price, price.observed_at, price.source_type, price.verification_status,
                   promotion.promo_type AS promotion_type, promotion.promo_price AS promotion_price,
                   promotion.min_quantity, promotion.end_date AS promotion_ends
            FROM stores s JOIN retailers r ON r.id = s.retailer_id
            CROSS JOIN LATERAL (
                SELECT DISTINCT product_id FROM price_observations
                WHERE store_id = s.id AND observed_at BETWEEN CURRENT_TIMESTAMP - make_interval(days => :days) AND CURRENT_TIMESTAMP
            ) recent
            JOIN products p ON p.id = recent.product_id
            CROSS JOIN LATERAL current_branch_price(p.id, s.id, :days) price
            LEFT JOIN LATERAL (
                SELECT offer.promo_type, offer.promo_price, offer.min_quantity, offer.end_date
                FROM current_branch_promotions offer
                WHERE offer.store_id = s.id AND offer.product_id = p.id
                ORDER BY offer.promo_type = 'DISCOUNT' DESC, offer.end_date, offer.id LIMIT 1
            ) promotion ON TRUE
            WHERE s.id = :id AND s.active AND r.active AND upper(s.store_code) IS DISTINCT FROM 'ONLINE'
            ORDER BY price.observed_at DESC, p.id LIMIT :limit OFFSET :offset
            """).param("id", id).param("days", maxAgeDays).param("limit", limit).param("offset", offset)
                .query(BranchOffer.class).list();
    }

    @GetMapping("/{id}/observations")
    public List<BranchObservation> observations(@PathVariable long id, @RequestParam(defaultValue = "0") int offset,
                                               @RequestParam(defaultValue = "25") int limit) {
        validate(null, null, 15, offset, limit);
        return jdbc.sql("""
            SELECT p.id AS product_id, p.canonical_name, p.brand, p.size_text, p.barcode, po.shelf_price,
                   po.observed_at, po.source_type, po.verification_status, po.stock_status
            FROM stores s JOIN retailers r ON r.id = s.retailer_id
            JOIN price_observations po ON po.store_id = s.id
            JOIN products p ON p.id = po.product_id
            WHERE s.id = :id AND s.active AND r.active AND upper(s.store_code) IS DISTINCT FROM 'ONLINE'
              AND po.observed_at <= CURRENT_TIMESTAMP AND po.verification_status <> 'DISPUTED'
            ORDER BY po.observed_at DESC, po.id DESC LIMIT :limit OFFSET :offset
            """).param("id", id).param("limit", limit).param("offset", offset)
                .query(BranchObservation.class).list();
    }

    @GetMapping("/areas")
    public List<LocalityDirectory.Place> areas(@RequestParam String q, @RequestParam(required = false) String province) {
        if (q.strip().length() < 2 || q.length() > 100) return List.of();
        var places = localities.find(q, filter(province));
        if (!places.isEmpty()) return places;
        return jdbc.sql("""
            SELECT 'branch-' || s.id AS id, coalesce(s.city, s.name) AS city, coalesce(s.province, '') AS province,
                   s.name AS area, ST_Y(s.location) AS lat, ST_X(s.location) AS lon, 'BRANCH' AS kind
            FROM stores s JOIN retailers r ON r.id = s.retailer_id
            WHERE s.active AND r.active AND upper(s.store_code) IS DISTINCT FROM 'ONLINE'
              AND (CAST(:province AS text) IS NULL OR lower(s.province) = lower(:province))
              AND strpos(lower(concat_ws(' ', s.name, s.address, s.city, s.postal_code)), lower(:q)) > 0
            ORDER BY s.name, s.id LIMIT 20
            """).param("province", filter(province)).param("q", q.strip()).query(LocalityDirectory.Place.class).list();
    }

    @GetMapping("/provinces")
    public List<String> provinces() {
        return localities.provinces();
    }

    private MapSqlParameterSource parameters(Double lat, Double lon, Long productId) {
        return new MapSqlParameterSource().addValue("lat", lat, Types.DOUBLE).addValue("lon", lon, Types.DOUBLE)
                .addValue("productId", productId, Types.BIGINT).addValue("days", maxAgeDays);
    }

    private static String filter(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 100) throw new IllegalArgumentException("Filter is too long");
        return value.strip();
    }

    private static void validate(Double lat, Double lon, double radius, int offset, int limit) {
        if ((lat == null) != (lon == null) || (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180))
                || !Double.isFinite(radius) || radius <= 0 || radius > 100
                || offset < 0 || offset > 10000 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid location, radius or page");
        }
    }

    public record NearbyStore(long id, String name, String retailer, String retailerSlug, String logoUrl,
            String address, String city, String province, String postalCode, String phone, String hoursText,
            String sourceUrl, double lat, double lon, Double distanceKm, long recordedProducts, long eligibleProducts,
            long verifiedProducts, long recentProducts, OffsetDateTime latestObserved, OffsetDateTime latestEligible,
            BigDecimal productPrice) {}
    public record BranchObservation(long productId, String canonicalName, String brand, String sizeText,
            String barcode, BigDecimal shelfPrice, OffsetDateTime observedAt, String sourceType,
            String verificationStatus, String stockStatus) {}
    public record BranchOffer(long productId, String canonicalName, String brand, String sizeText, String imageUrl,
            String barcode, BigDecimal shelfPrice, BigDecimal effectivePrice, BigDecimal memberPrice,
            OffsetDateTime observedAt, String sourceType, String verificationStatus, String promotionType, BigDecimal promotionPrice, Integer minQuantity,
            java.time.LocalDate promotionEnds) {}
}
