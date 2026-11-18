package za.co.shoppingdiary.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import za.co.shoppingdiary.product.dto.PriceHistoryRow;
import za.co.shoppingdiary.product.dto.ProductSearchHit;
import za.co.shoppingdiary.product.dto.StorePriceRow;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final NamedParameterJdbcTemplate jdbc;

    public List<ProductSearchHit> search(String query, double lat, double lon, double radiusKm) {
        String sql = """
                WITH user_point AS (
                    SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS g
                ),
                nearby_stores AS (
                    SELECT s.id
                    FROM stores s, user_point up
                    WHERE s.active
                      AND ST_DWithin(s.location::geography, up.g, :radiusMeters)
                ),
                latest_prices AS (
                    SELECT DISTINCT ON (po.product_id, po.store_id)
                           po.product_id, po.store_id,
                           po.shelf_price, po.promo_price, po.observed_at
                    FROM price_observations po
                    JOIN nearby_stores ns ON ns.id = po.store_id
                    ORDER BY po.product_id, po.store_id, po.observed_at DESC
                )
                SELECT p.id, p.canonical_name, p.brand, p.barcode, p.size_text, p.image_url, p.category,
                   COUNT(DISTINCT lp.store_id) AS store_count,
                       MIN(COALESCE(lp.promo_price, lp.shelf_price)) AS min_price,
                       MAX(lp.observed_at) AS latest_observed_at
                FROM products p
                JOIN latest_prices lp ON lp.product_id = p.id
                WHERE p.canonical_name ILIKE '%' || :q || '%'
                GROUP BY p.id, p.canonical_name, p.brand, p.barcode, p.size_text
                ORDER BY word_similarity(:q, p.canonical_name) DESC,
                         min_price ASC NULLS LAST
                LIMIT 20
                """;

        var params = new MapSqlParameterSource()
                .addValue("q", query)
                .addValue("lat", lat)
                .addValue("lon", lon)
                .addValue("radiusMeters", radiusKm * 1000);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            ProductSearchHit h = new ProductSearchHit();
            h.setProductId(rs.getLong("id"));
            h.setCanonicalName(rs.getString("canonical_name"));
            h.setBrand(rs.getString("brand"));
            h.setBarcode(rs.getString("barcode"));
            h.setSizeText(rs.getString("size_text"));
            h.setStoreCount(rs.getInt("store_count"));
            h.setMinEffectivePrice(rs.getBigDecimal("min_price"));
            OffsetDateTime ts = rs.getObject("latest_observed_at", OffsetDateTime.class);
            h.setLatestObservedAt(ts != null ? ts.toInstant() : null);
            h.setImageUrl(rs.getString("image_url"));
            h.setCategory(rs.getString("category"));
            return h;
        });
    }

    public List<StorePriceRow> pricesForProduct(long productId, double lat, double lon, double radiusKm) {
        String sql = """
                WITH user_point AS (
                    SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS g
                )
                SELECT s.id AS store_id,
                       s.name AS store_name,
                       r.name AS retailer_name,
                       ST_Distance(s.location::geography, up.g) AS distance_m,
                       po.shelf_price, po.promo_price, po.member_price, po.unit_price,
                       po.observed_at, po.verification_status, po.stock_status
                FROM stores s
                CROSS JOIN user_point up
                JOIN retailers r ON r.id = s.retailer_id
                JOIN LATERAL (
                    SELECT * FROM price_observations
                    WHERE product_id = :productId AND store_id = s.id
                    ORDER BY observed_at DESC
                    LIMIT 1
                ) po ON true
                WHERE s.active
                  AND ST_DWithin(s.location::geography, up.g, :radiusMeters)
                ORDER BY COALESCE(po.promo_price, po.shelf_price) ASC NULLS LAST,
                         distance_m ASC
                """;

        var params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("lat", lat)
                .addValue("lon", lon)
                .addValue("radiusMeters", radiusKm * 1000);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            StorePriceRow r = new StorePriceRow();
            r.setStoreId(rs.getLong("store_id"));
            r.setStoreName(rs.getString("store_name"));
            r.setRetailerName(rs.getString("retailer_name"));
            r.setDistanceMeters(rs.getDouble("distance_m"));
            r.setShelfPrice(rs.getBigDecimal("shelf_price"));
            r.setPromoPrice(rs.getBigDecimal("promo_price"));
            r.setMemberPrice(rs.getBigDecimal("member_price"));
            r.setUnitPrice(rs.getBigDecimal("unit_price"));
            OffsetDateTime ts = rs.getObject("observed_at", OffsetDateTime.class);
            r.setObservedAt(ts != null ? ts.toInstant() : null);
            r.setVerificationStatus(rs.getString("verification_status"));
            r.setStockStatus(rs.getString("stock_status"));
            return r;
        });
    }

    public List<PriceHistoryRow> priceHistory(long productId) {
        String sql = """
                SELECT s.name AS store_name,
                       COALESCE(po.promo_price, po.shelf_price) AS price,
                       po.observed_at
                FROM price_observations po
                JOIN stores s ON s.id = po.store_id
                WHERE po.product_id = :productId
                ORDER BY po.observed_at ASC
                """;
        return jdbc.query(sql, new MapSqlParameterSource("productId", productId), (rs, rowNum) -> {
            return new PriceHistoryRow(
                    rs.getString("store_name"),
                    rs.getBigDecimal("price"),
                    rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant());
        });
    }
}