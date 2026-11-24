
package za.co.shoppingdiary.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import za.co.shoppingdiary.product.dto.PriceHistoryRow;
import za.co.shoppingdiary.product.dto.ProductSearchHit;
import za.co.shoppingdiary.product.dto.StorePriceRow;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final NamedParameterJdbcTemplate jdbc;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxAgeDays = 7;

    private static final Pattern SIZE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])\\d+(?:[.,]\\d+)?\\s*(?:kg|g|ml|l)(?![\\p{L}\\d])");

    public record SearchTerms(String text, String barcode, String size) {
        public static SearchTerms parse(String query) {
            String cleaned = query.strip().toLowerCase(Locale.ROOT);
            if (cleaned.matches("[0-9]{8,20}")) {
                return new SearchTerms("", cleaned, null);
            }
            var match = SIZE.matcher(cleaned);
            String size = match.find() ? match.group() : null;
            String text = size == null ? cleaned : match.replaceFirst(" ");
            return new SearchTerms(text.replaceAll("[^\\p{L}\\p{N}]+", " ").strip(), null, size);
        }
    }

    // Select the latest non-future observation before checking eligibility. A newer
    // out-of-stock or disputed observation supersedes an older usable price.
    private static final String ELIGIBLE_STORE_PRICE = """
        JOIN LATERAL (
            SELECT latest.*, COALESCE(latest.promo_price, latest.shelf_price) AS effective_price
            FROM (
                SELECT CASE WHEN shelf_price > 0 THEN shelf_price END AS shelf_price,
                    CASE WHEN promo_price > 0 AND (shelf_price IS NULL OR shelf_price <= 0
                        OR promo_price <= shelf_price) THEN promo_price END AS promo_price,
                    member_price, unit_price, observed_at, verification_status, stock_status
                FROM price_observations
                WHERE product_id = %s AND store_id = s.id AND observed_at <= CURRENT_TIMESTAMP
                ORDER BY observed_at DESC, id DESC
                LIMIT 1
            ) latest
            WHERE latest.observed_at >= CURRENT_TIMESTAMP - make_interval(days => :maxAgeDays)
              AND latest.stock_status = 'IN_STOCK'
              AND latest.verification_status IN ('VERIFIED', 'UNVERIFIED')
              AND (latest.shelf_price IS NOT NULL OR latest.promo_price IS NOT NULL)
        ) po ON TRUE
        """;

    private static final String CATALOGUE = """
        WITH RECURSIVE departments AS (
            SELECT id, name AS department FROM categories WHERE parent_id IS NULL
            UNION ALL
            SELECT c.id, d.department FROM categories c JOIN departments d ON c.parent_id = d.id
        ),
        catalogue AS NOT MATERIALIZED (
            SELECT p.*, COALESCE(c.name, NULLIF(btrim(p.category), '')) AS category_name,
                COALESCE(d.department, c.name, NULLIF(btrim(p.category), '')) AS department,
                COALESCE(NULLIF(btrim(p.size_text), ''), trim_scale(p.base_quantity)::text || ' ' || p.base_unit,
                    CASE WHEN p.canonical_name !~* '[0-9][[:space:]]*[x?][[:space:]]*[0-9]' THEN
                        substring(p.canonical_name FROM '(?i)[[:space:]]([0-9]+(?:[.,][0-9]+)?[[:space:]]*(?:kg|g|ml|l))[[:space:]]*$')
                    END) AS package_label
            FROM products p
            LEFT JOIN categories c ON c.id = p.category_id
            LEFT JOIN departments d ON d.id = c.id
        )
        """;

    // Narrow text searches on the longest token before evaluating every token.
    private static final String TEXT_CANDIDATES = """
        AND p.id IN (
            SELECT id FROM products
            WHERE lower(canonical_name || ' ' || coalesce(brand, '')) LIKE :anchorPattern
               OR lower(canonical_name || ' ' || coalesce(brand, '')) %> :anchor
            UNION
            SELECT id FROM products
            WHERE category_id IS NULL AND category IS NOT NULL
              AND (lower(btrim(category)) LIKE :anchorPattern OR lower(btrim(category)) %> :anchor)
            UNION
            SELECT item.id
            FROM categories c
            LEFT JOIN departments d ON d.id = c.id
            JOIN products item ON item.category_id = c.id
            WHERE lower(c.name) LIKE :anchorPattern OR lower(c.name) %> :anchor
               OR lower(d.department) LIKE :anchorPattern OR lower(d.department) %> :anchor
        )
        """;

    public List<String> categories() {
        return jdbc.queryForList(CATALOGUE + """
            SELECT min(label) FROM (
                SELECT name AS label FROM categories WHERE parent_id IS NULL
                UNION ALL
                SELECT department FROM catalogue WHERE department IS NOT NULL
                UNION ALL
                SELECT category_name FROM catalogue WHERE category_name IS NOT NULL
            ) labels
            GROUP BY lower(label)
            ORDER BY lower(label)
            """, new MapSqlParameterSource(), String.class);
    }

    public List<ProductSearchHit> search(String query, double lat, double lon, double radiusKm) {
        return search(query, lat, lon, radiusKm, null, null, null, null, 0, 30);
    }

    public List<ProductSearchHit> search(
            String query, Double lat, Double lon, double radiusKm, String category,
            String brand, String size, Boolean priced, int offset, int limit
    ) {
        SearchTerms terms = SearchTerms.parse(query);
        String anchor = Arrays.stream(terms.text().split(" "))
                .max(Comparator.comparingInt(String::length)).orElse("");
        String sql = CATALOGUE + """
            , nearby_stores AS MATERIALIZED (
                SELECT id FROM stores
                WHERE active AND ST_DWithin(location::geography,
                    ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ),
            matches AS (
                SELECT p.*, relevance.score,
                    CASE WHEN lower(p.canonical_name) = :text THEN 0
                         WHEN strpos(lower(p.canonical_name), :text) > 0 THEN 1 ELSE 2 END AS name_rank
                FROM catalogue p
                CROSS JOIN LATERAL (
                    SELECT lower(p.canonical_name || ' ' || coalesce(p.brand, '')) AS document,
                        lower(p.category_name) AS category, lower(p.department) AS department
                ) search
                CROSS JOIN LATERAL (
                    SELECT min(CASE
                        WHEN strpos(search.document, token) > 0
                          OR strpos(search.category, token) > 0 OR strpos(search.department, token) > 0 THEN 1.0
                        WHEN length(token) >= 4 AND (search.document %%> token
                          OR search.category %%> token OR search.department %%> token)
                        THEN greatest(word_similarity(token, search.document), word_similarity(token, search.category),
                                      word_similarity(token, search.department))
                        ELSE 0 END) AS score
                    FROM unnest(string_to_array(:text, ' ')) AS words(token)
                ) relevance
                WHERE (CAST(:barcode AS text) IS NULL OR p.barcode = :barcode)
                  AND (:text = '' OR relevance.score > 0)
                  %s
                  AND (CAST(:category AS text) IS NULL OR lower(:category) IN
                       (lower(p.category_name), lower(p.department)))
                  AND (CAST(:brand AS text) IS NULL OR strpos(lower(p.brand), lower(:brand)) > 0)
                  AND (CAST(:querySize AS text) IS NULL OR catalogue_package_size(:querySize) IN (
                       catalogue_package_size(p.package_label),
                       catalogue_package_size(trim_scale(p.base_quantity)::text || p.base_unit)))
                  AND (CAST(:size AS text) IS NULL OR catalogue_package_size(:size) IN (
                       catalogue_package_size(p.package_label),
                       catalogue_package_size(trim_scale(p.base_quantity)::text || p.base_unit)))
                ORDER BY relevance.score DESC NULLS LAST, name_rank, p.id
                LIMIT CASE WHEN CAST(:priced AS boolean) IS NULL THEN :window END
            )
            SELECT p.*, prices.store_count, prices.min_price, prices.latest_observed_at
            FROM matches p
            CROSS JOIN LATERAL (
                SELECT count(*) AS store_count, min(effective_price) AS min_price,
                       max(observed_at) AS latest_observed_at
                FROM nearby_stores s
                %s
            ) prices
            WHERE CAST(:priced AS boolean) IS NULL OR :priced = (prices.store_count > 0)
            ORDER BY p.score DESC NULLS LAST, p.name_rank, p.id
            LIMIT :limit OFFSET :offset
            """.formatted(anchor.isEmpty() ? "" : TEXT_CANDIDATES, ELIGIBLE_STORE_PRICE.formatted("p.id"));

        var params = new MapSqlParameterSource()
                .addValue("text", terms.text())
                .addValue("anchor", anchor)
                .addValue("anchorPattern", "%" + anchor + "%")
                .addValue("barcode", terms.barcode(), Types.VARCHAR)
                .addValue("querySize", terms.size(), Types.VARCHAR)
                .addValue("category", category, Types.VARCHAR)
                .addValue("brand", brand, Types.VARCHAR)
                .addValue("size", size, Types.VARCHAR)
                .addValue("priced", priced, Types.BOOLEAN)
                .addValue("offset", offset)
                .addValue("limit", limit)
                .addValue("window", offset + limit)
                .addValue("lat", lat, Types.DOUBLE)
                .addValue("lon", lon, Types.DOUBLE)
                .addValue("maxAgeDays", maxAgeDays)
                .addValue("radiusMeters", radiusKm * 1000);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            var hit = new ProductSearchHit();
            hit.setProductId(rs.getLong("id"));
            hit.setCanonicalName(rs.getString("canonical_name"));
            hit.setBrand(rs.getString("brand"));
            hit.setBarcode(rs.getString("barcode"));
            hit.setSizeText(rs.getString("package_label"));
            hit.setImageUrl(rs.getString("image_url"));
            hit.setCategory(rs.getString("category_name"));
            hit.setSourceUrl(rs.getString("catalogue_source_url"));
            hit.setImageSourceUrl(rs.getString("image_source_url"));
            hit.setStoreCount(rs.getInt("store_count"));
            hit.setMinEffectivePrice(rs.getBigDecimal("min_price"));
            var observed = rs.getObject("latest_observed_at", OffsetDateTime.class);
            hit.setLatestObservedAt(observed == null ? null : observed.toInstant());
            return hit;
        });
    }

    public List<StorePriceRow> pricesForProduct(
            long productId,
            double lat,
            double lon,
            double radiusKm
    ) {
        String sql = """
            WITH user_point AS (
                SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS location
            )
            SELECT s.id AS store_id, s.name AS store_name, r.name AS retailer_name,
                   ST_Distance(s.location::geography, up.location) AS distance_m, po.*
            FROM stores s
            CROSS JOIN user_point up
            JOIN retailers r ON r.id = s.retailer_id
            %s
            WHERE s.active AND ST_DWithin(s.location::geography, up.location, :radiusMeters)
            ORDER BY po.effective_price, distance_m, s.id
            """.formatted(ELIGIBLE_STORE_PRICE.formatted(":productId"));

        var params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("maxAgeDays", maxAgeDays)
                .addValue("lat", lat)
                .addValue("lon", lon)
                .addValue("radiusMeters", radiusKm * 1000);

        List<StorePriceRow> prices = jdbc.query(sql, params, (rs, rowNum) -> {
            var row = new StorePriceRow();
            row.setStoreId(rs.getLong("store_id"));
            row.setStoreName(rs.getString("store_name"));
            row.setRetailerName(rs.getString("retailer_name"));
            row.setDistanceMeters(rs.getDouble("distance_m"));
            row.setShelfPrice(rs.getBigDecimal("shelf_price"));
            row.setPromoPrice(rs.getBigDecimal("promo_price"));
            row.setMemberPrice(rs.getBigDecimal("member_price"));
            row.setUnitPrice(rs.getBigDecimal("unit_price"));
            row.setObservedAt(rs.getObject("observed_at", OffsetDateTime.class).toInstant());
            row.setVerificationStatus(rs.getString("verification_status"));
            row.setStockStatus(rs.getString("stock_status"));
            return row;
        });
        if (!prices.isEmpty()) {
            addActivePromotions(productId, prices);
        }
        return prices;
    }

    private void addActivePromotions(long productId, List<StorePriceRow> prices) {
        var byStore = new HashMap<Long, StorePriceRow>();
        prices.forEach(price -> byStore.put(price.getStoreId(), price));

        jdbc.query("""
            SELECT store_id, promo_type, description, promo_price, min_quantity, end_date
            FROM promotions
            WHERE product_id = :productId AND store_id IN (:storeIds)
              AND status = 'ACTIVE'
              AND (CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Johannesburg')::date BETWEEN start_date AND end_date
              AND promo_price > 0
              AND (promo_type <> 'MULTIBUY' OR min_quantity > 1)
            ORDER BY end_date, promo_price, id
            """, new MapSqlParameterSource("productId", productId).addValue("storeIds", byStore.keySet()),
            rs -> {
                byStore.get(rs.getLong("store_id")).getPromotions().add(new StorePriceRow.Promotion(
                        rs.getString("promo_type"), rs.getString("description"), rs.getBigDecimal("promo_price"),
                        rs.getObject("min_quantity", Integer.class), rs.getObject("end_date", LocalDate.class)));
            });
    }

    public List<PriceHistoryRow> priceHistory(
            long productId
    ) {
        String sql = """
            SELECT
                s.name AS store_name,

                CASE
                    WHEN po.promo_price > 0 AND (po.shelf_price IS NULL OR po.shelf_price <= 0
                         OR po.promo_price <= po.shelf_price) THEN po.promo_price
                    WHEN po.shelf_price > 0 THEN po.shelf_price
                END AS price,

                po.observed_at

            FROM price_observations po

            JOIN stores s
                ON s.id = po.store_id

            WHERE po.product_id = :productId
              AND po.observed_at <= CURRENT_TIMESTAMP
              AND po.verification_status NOT IN ('DISPUTED', 'EXPIRED')
              AND (po.shelf_price > 0 OR po.promo_price > 0)
            ORDER BY po.observed_at ASC, po.id ASC
            """;

        return jdbc.query(
                sql,
                new MapSqlParameterSource("productId", productId),
                (rs, rowNum) ->
                        new PriceHistoryRow(
                                rs.getString("store_name"),
                                rs.getBigDecimal("price"),
                                rs.getObject(
                                        "observed_at",
                                        OffsetDateTime.class
                                ).toInstant()
                        )
        );
    }
}
