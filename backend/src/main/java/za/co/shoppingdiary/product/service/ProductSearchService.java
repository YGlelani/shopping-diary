
package za.co.shoppingdiary.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.shoppingdiary.product.dto.PriceHistoryRow;
import za.co.shoppingdiary.product.dto.ProductSearchHit;
import za.co.shoppingdiary.product.dto.StorePriceRow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductSearchService {

    private final NamedParameterJdbcTemplate jdbc;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxPriceAgeDays;

    private void validateLocation(
            double lat,
            double lon,
            double radiusKm
    ) {
        if (!Double.isFinite(lat)
                || !Double.isFinite(lon)
                || lat < -90 || lat > 90
                || lon < -180 || lon > 180) {
            throw new IllegalArgumentException(
                    "Invalid latitude or longitude"
            );
        }

        if (!Double.isFinite(radiusKm)
                || radiusKm <= 0
                || radiusKm > 100) {
            throw new IllegalArgumentException(
                    "Radius must be between 0 and 100 km"
            );
        }

        if (maxPriceAgeDays <= 0) {
            throw new IllegalStateException(
                    "Price freshness period must be positive"
            );
        }
    }

    private MapSqlParameterSource locationParams(
            double lat,
            double lon,
            double radiusKm
    ) {
        return new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lon", lon)
                .addValue(
                        "radiusMeters",
                        radiusKm * 1000.0
                )
                .addValue("maxAgeDays", maxPriceAgeDays);
    }

    public List<ProductSearchHit> search(
            String query,
            double lat,
            double lon,
            double radiusKm
    ) {
        validateLocation(lat, lon, radiusKm);

        if (query == null
                || query.trim().length() < 2) {
            throw new IllegalArgumentException(
                    "Search must contain at least 2 characters"
            );
        }

        String sql = """
            WITH nearby_stores AS (
                SELECT s.id
                FROM stores s
                JOIN retailers r ON r.id = s.retailer_id
                WHERE s.active = TRUE
                  AND r.active = TRUE
                  AND ST_DWithin(
                      s.location::geography,
                      ST_SetSRID(
                          ST_MakePoint(:lon, :lat),
                          4326
                      )::geography,
                      :radiusMeters
                  )
            ),
            latest AS (
                SELECT DISTINCT ON (
                    po.product_id,
                    po.store_id
                )
                    po.product_id,
                    po.store_id,
                    po.shelf_price,
                    po.promo_price,
                    po.observed_at,
                    po.stock_status,
                    po.verification_status
                FROM price_observations po
                JOIN nearby_stores ns
                    ON ns.id = po.store_id
                ORDER BY
                    po.product_id,
                    po.store_id,
                    po.observed_at DESC,
                    po.id DESC
            ),
            eligible AS (
                SELECT
                    *,
                    CASE
                        WHEN promo_price > 0
                             AND shelf_price > 0
                            THEN LEAST(
                                promo_price,
                                shelf_price
                            )
                        WHEN promo_price > 0
                            THEN promo_price
                        WHEN shelf_price > 0
                            THEN shelf_price
                        ELSE NULL
                    END AS effective_price
                FROM latest
                WHERE observed_at >= (
                    CURRENT_TIMESTAMP
                    - (:maxAgeDays * INTERVAL '1 day')
                )
                  AND observed_at <= CURRENT_TIMESTAMP
                  AND stock_status = 'IN_STOCK'
                  AND verification_status IN (
                      'VERIFIED',
                      'UNVERIFIED'
                  )
            )
            SELECT
                p.id,
                p.canonical_name,
                p.brand,
                p.barcode,
                p.size_text,
                p.image_url,
                p.category,
                COUNT(DISTINCT e.store_id) AS store_count,
                MIN(e.effective_price) AS min_price,
                MAX(e.observed_at) AS latest_observed_at
            FROM products p
            JOIN eligible e ON e.product_id = p.id
            WHERE e.effective_price IS NOT NULL
              AND (
                  p.canonical_name ILIKE
                      '%' || :q || '%'
                  OR p.brand ILIKE
                      '%' || :q || '%'
                  OR p.barcode = :q
              )
            GROUP BY p.id
            ORDER BY
                word_similarity(
                    :q,
                    p.canonical_name
                ) DESC,
                min_price ASC
            LIMIT 20
            """;

        MapSqlParameterSource params =
                locationParams(lat, lon, radiusKm)
                        .addValue("q", query.trim());

        return jdbc.query(sql, params, (rs, rowNum) -> {
            ProductSearchHit hit =
                    new ProductSearchHit();

            hit.setProductId(rs.getLong("id"));
            hit.setCanonicalName(
                    rs.getString("canonical_name")
            );
            hit.setBrand(rs.getString("brand"));
            hit.setBarcode(rs.getString("barcode"));
            hit.setSizeText(rs.getString("size_text"));
            hit.setImageUrl(rs.getString("image_url"));
            hit.setCategory(rs.getString("category"));
            hit.setStoreCount(rs.getInt("store_count"));
            hit.setMinEffectivePrice(
                    rs.getBigDecimal("min_price")
            );

            OffsetDateTime timestamp =
                    rs.getObject(
                            "latest_observed_at",
                            OffsetDateTime.class
                    );

            hit.setLatestObservedAt(
                    timestamp == null
                            ? null
                            : timestamp.toInstant()
            );

            return hit;
        });
    }

    public List<StorePriceRow> pricesForProduct(
            long productId,
            double lat,
            double lon,
            double radiusKm
    ) {
        validateLocation(lat, lon, radiusKm);

        if (productId <= 0) {
            throw new IllegalArgumentException(
                    "Invalid product ID"
            );
        }

        String sql = """
            SELECT
                s.id AS store_id,
                s.name AS store_name,
                r.name AS retailer_name,
                ST_Distance(
                    s.location::geography,
                    ST_SetSRID(
                        ST_MakePoint(:lon, :lat),
                        4326
                    )::geography
                ) AS distance_m,
                po.shelf_price,
                po.promo_price,
                po.member_price,
                po.unit_price,
                po.observed_at,
                po.verification_status,
                po.stock_status
            FROM stores s
            JOIN retailers r
                ON r.id = s.retailer_id
            JOIN LATERAL (
                SELECT *
                FROM price_observations
                WHERE product_id = :productId
                  AND store_id = s.id
                ORDER BY observed_at DESC, id DESC
                LIMIT 1
            ) po ON TRUE
            WHERE s.active = TRUE
              AND r.active = TRUE
              AND ST_DWithin(
                  s.location::geography,
                  ST_SetSRID(
                      ST_MakePoint(:lon, :lat),
                      4326
                  )::geography,
                  :radiusMeters
              )
              AND po.observed_at >= (
                  CURRENT_TIMESTAMP
                  - (:maxAgeDays * INTERVAL '1 day')
              )
              AND po.observed_at <= CURRENT_TIMESTAMP
              AND po.stock_status = 'IN_STOCK'
              AND po.verification_status IN (
                  'VERIFIED',
                  'UNVERIFIED'
              )
              AND (
                  po.shelf_price > 0
                  OR po.promo_price > 0
              )
            ORDER BY
                CASE
                    WHEN po.promo_price > 0
                         AND po.shelf_price > 0
                        THEN LEAST(
                            po.promo_price,
                            po.shelf_price
                        )
                    WHEN po.promo_price > 0
                        THEN po.promo_price
                    ELSE po.shelf_price
                END ASC,
                distance_m ASC
            """;

        MapSqlParameterSource params =
                locationParams(lat, lon, radiusKm)
                        .addValue("productId", productId);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            StorePriceRow row = new StorePriceRow();

            row.setStoreId(rs.getLong("store_id"));
            row.setStoreName(
                    rs.getString("store_name")
            );
            row.setRetailerName(
                    rs.getString("retailer_name")
            );
            row.setDistanceMeters(
                    rs.getDouble("distance_m")
            );
            row.setShelfPrice(
                    rs.getBigDecimal("shelf_price")
            );
            row.setPromoPrice(
                    rs.getBigDecimal("promo_price")
            );
            row.setMemberPrice(
                    rs.getBigDecimal("member_price")
            );
            row.setUnitPrice(
                    rs.getBigDecimal("unit_price")
            );
            row.setVerificationStatus(
                    rs.getString("verification_status")
            );
            row.setStockStatus(
                    rs.getString("stock_status")
            );

            OffsetDateTime timestamp =
                    rs.getObject(
                            "observed_at",
                            OffsetDateTime.class
                    );

            row.setObservedAt(
                    timestamp == null
                            ? null
                            : timestamp.toInstant()
            );

            return row;
        });
    }

    public List<PriceHistoryRow> priceHistory(
            long productId
    ) {
        if (productId <= 0) {
            throw new IllegalArgumentException(
                    "Invalid product ID"
            );
        }

        String sql = """
            SELECT
                s.name AS store_name,
                CASE
                    WHEN po.promo_price > 0
                         AND po.shelf_price > 0
                        THEN LEAST(
                            po.promo_price,
                            po.shelf_price
                        )
                    WHEN po.promo_price > 0
                        THEN po.promo_price
                    WHEN po.shelf_price > 0
                        THEN po.shelf_price
                    ELSE NULL
                END AS price,
                po.observed_at
            FROM price_observations po
            JOIN stores s
                ON s.id = po.store_id
            WHERE po.product_id = :productId
              AND po.verification_status NOT IN (
                  'DISPUTED', 'EXPIRED'
              )
            ORDER BY po.observed_at ASC, po.id ASC
            """;

        return jdbc.query(
                sql,
                new MapSqlParameterSource(
                        "productId",
                        productId
                ),
                (rs, rowNum) -> {
                    OffsetDateTime timestamp =
                            rs.getObject(
                                    "observed_at",
                                    OffsetDateTime.class
                            );

                    Instant observedAt =
                            timestamp == null
                                    ? null
                                    : timestamp.toInstant();

                    return new PriceHistoryRow(
                            rs.getString("store_name"),
                            rs.getBigDecimal("price"),
                            observedAt
                    );
                }
        );
    }
}
