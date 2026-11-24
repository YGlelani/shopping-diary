package za.co.shoppingdiary.list.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShoppingEstimateService {
    private final JdbcClient jdbc;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxAgeDays;

    public record Line(long listId, long itemId, String requestedName, Long productId, String productName,
                       Long storeId, String storeName, BigDecimal quantity, BigDecimal total,
                       OffsetDateTime observedAt, String sourceType, String verificationStatus,
                       LocalDate promotionEndsOn, String unavailableReason) {}

    public List<Line> estimate(long owner, List<Long> listIds, Double lat, Double lon, double radius,
                               Long branchId, boolean usePreferredLocation) {
        return estimate(owner,listIds,lat,lon,radius,branchId,usePreferredLocation,Set.of());
    }

    public List<Line> estimate(long owner,List<Long> listIds,Double lat,Double lon,double radius,
                               Long branchId,boolean usePreferredLocation,Set<Long> loyaltyRetailers) {
        if (loyaltyRetailers.size()>250 || loyaltyRetailers.stream().anyMatch(id -> id==null || id<=0)) {
            throw new IllegalArgumentException("Invalid confirmed loyalty retailers");
        }
        if ((lat == null) != (lon == null) || (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180))
                || !Double.isFinite(radius) || radius <= 0 || radius > 100
                || (branchId != null && branchId <= 0)) {
            throw new IllegalArgumentException("Invalid estimate location or branch");
        }
        if (listIds.isEmpty()) return List.of();
        var parameters = new MapSqlParameterSource("owner", owner).addValue("lists", listIds)
                .addValue("lat", lat, Types.DOUBLE).addValue("lon", lon, Types.DOUBLE)
                .addValue("branch", branchId, Types.BIGINT).addValue("preferred", usePreferredLocation)
                .addValue("radius", radius * 1000).addValue("age", maxAgeDays)
                .addValue("loyalty",loyaltyRetailers.isEmpty() ? Set.of(-1L) : loyaltyRetailers);

        return jdbc.sql("""
            WITH owned AS MATERIALIZED (
                SELECT l.id, ST_SetSRID(ST_MakePoint(
                    CASE WHEN :preferred THEN coalesce(l.location_lon,:lon) ELSE :lon END,
                    CASE WHEN :preferred THEN coalesce(l.location_lat,:lat) ELSE :lat END),4326)::geography AS point
                FROM shopping_lists l WHERE l.id IN (:lists) AND l.user_id = :owner
            ),
            eligible_stores AS MATERIALIZED (
                SELECT l.id AS list_id, s.id, s.name,s.retailer_id
                FROM owned l CROSS JOIN stores s JOIN retailers r ON r.id = s.retailer_id
                WHERE s.active AND r.active AND upper(s.store_code) IS DISTINCT FROM 'ONLINE'
                  AND ((:branch IS NOT NULL AND s.id = :branch
                        AND (l.point IS NULL OR ST_DWithin(s.location::geography,l.point,:radius)))
                    OR (:branch IS NULL AND ST_DWithin(s.location::geography,l.point,:radius)))
            ),
            best AS (
                SELECT DISTINCT ON (li.id) li.id AS item_id, s.id AS store_id, s.name AS store_name,
                       offer.product_id, offer.product_name, offer.total, offer.promotion_ends_on
                FROM shopping_list_items li JOIN eligible_stores s ON s.list_id = li.list_id
                CROSS JOIN LATERAL shopping_list_item_quote(li, s.id, :age,s.retailer_id IN (:loyalty)) offer
                WHERE li.product_id IS NOT NULL
                ORDER BY li.id, offer.total, (offer.product_id = li.product_id) DESC, s.id
            )
            SELECT li.list_id, li.id AS item_id, coalesce(p.canonical_name,li.free_text) AS requested_name,
                   li.quantity, best.product_id, best.product_name, best.store_id, best.store_name, best.total,
                   observed.observed_at, observed.source_type, observed.verification_status,
                   best.promotion_ends_on,
                   CASE WHEN best.total IS NOT NULL THEN NULL
                        WHEN li.product_id IS NULL THEN 'UNLINKED'
                        WHEN history.has_stale THEN 'STALE_OBSERVATION'
                        ELSE 'NO_ELIGIBLE_PRICE' END AS unavailable_reason
            FROM owned l JOIN shopping_list_items li ON li.list_id = l.id
            LEFT JOIN products p ON p.id = li.product_id
            LEFT JOIN best ON best.item_id = li.id
            LEFT JOIN LATERAL eligible_branch_observation(best.product_id,best.store_id,:age) observed ON best.product_id IS NOT NULL
            LEFT JOIN LATERAL (
                SELECT bool_or(latest.observed_at < CURRENT_TIMESTAMP - make_interval(days =>
                    least(:age, CASE WHEN latest.source_type IN ('COMMUNITY','RECEIPT','BROCHURE') THEN 3 ELSE :age END)))
                    AS has_stale
                FROM eligible_stores s
                CROSS JOIN LATERAL (
                    SELECT po.source_type, po.observed_at FROM price_observations po
                    WHERE po.store_id=s.id AND po.product_id=li.product_id AND po.observed_at <= CURRENT_TIMESTAMP
                    ORDER BY po.observed_at DESC, po.id DESC LIMIT 1
                ) latest
                WHERE s.list_id=li.list_id AND best.total IS NULL
            ) history ON TRUE
            ORDER BY li.list_id, li.id
            """).paramSource(parameters).query(Line.class).list();
    }
}
