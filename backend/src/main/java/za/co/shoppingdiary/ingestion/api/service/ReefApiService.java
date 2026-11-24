
package za.co.shoppingdiary.ingestion.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReefApiService {

    private static final String PNP_PROMOTIONS_URL =
            "https://api.reefapi.com/pnp/v1/promotions";

    private static final ZoneId SOUTH_AFRICA =
            ZoneId.of("Africa/Johannesburg");

    private final JdbcTemplate jdbc;

    private final RestTemplate restTemplate = client();

    private static RestTemplate client() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }

    @Value("${reefapi.api.key:}")
    private String apiKey;

    @Value("${reefapi.pnp.store-code:}")
    private String defaultStoreCode;

    /**
     * Compatibility method for existing callers.
     */
    public void syncPromotions(String retailerSlug) {
        syncPromotions(retailerSlug, null);
    }

    /**
     * Sync one page of Pick n Pay promotions for a
     * specific, already-mapped store.
     *
     * Only exact, trusted product aliases are used.
     * The method deliberately skips promotions that
     * cannot be interpreted safely.
     */
    public Map<String, Object> syncPromotions(
            String retailerSlug,
            String requestedStoreCode
    ) {
        if (apiKey == null
                || apiKey.isBlank()
                || "your_reef_api_key_here".equals(apiKey)) {

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ReefAPI key is not configured"
            );
        }

        String retailer = normalizeRetailer(
                retailerSlug
        );

        if (!"picknpay".equals(retailer)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "This promotion importer currently supports "
                            + "Pick n Pay only. Other retailers "
                            + "require their own API adapter."
            );
        }

        String storeCode = requestedStoreCode;

        if (storeCode == null || storeCode.isBlank()) {
            storeCode = defaultStoreCode;
        }

        if (storeCode == null || storeCode.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A Pick n Pay store code is required"
            );
        }

        storeCode = storeCode.trim()
                .toUpperCase(Locale.ROOT);

        if (!storeCode.matches("[A-Z0-9_-]{2,30}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid Pick n Pay store code"
            );
        }

        // Do not invent a physical store or attach
        // a promotion to an arbitrary nearby branch.
        List<Long> storeIds = jdbc.queryForList(
                """
                SELECT s.id
                FROM stores s
                JOIN retailers r
                    ON r.id = s.retailer_id
                WHERE r.slug = 'picknpay'
                  AND r.active = TRUE
                  AND s.active = TRUE
                  AND UPPER(s.store_code) = ?
                ORDER BY s.id
                LIMIT 1
                """,
                Long.class,
                storeCode
        );

        if (storeIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Store code is not mapped to an active "
                            + "Pick n Pay branch in Shopping Diary"
            );
        }

        Long storeId = storeIds.get(0);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(
                List.of(MediaType.APPLICATION_JSON)
        );

        Map<String, Object> requestBody =
                new HashMap<>();

        requestBody.put("store_id", storeCode);
        requestBody.put("page", 1);
        requestBody.put("page_size", 24);

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(
                        requestBody,
                        headers
                );

        Map<?, ?> root;

        try {
            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            PNP_PROMOTIONS_URL,
                            HttpMethod.POST,
                            request,
                            Map.class
                    );

            root = response.getBody();

        } catch (HttpStatusCodeException ex) {
            log.warn(
                    "ReefAPI rejected promotion request: HTTP {}",
                    ex.getStatusCode().value()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ReefAPI request failed with HTTP "
                            + ex.getStatusCode().value(),
                    ex
            );

        } catch (RestClientException ex) {
            log.error(
                    "ReefAPI promotion request failed",
                    ex
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to contact ReefAPI",
                    ex
            );
        }

        if (root == null
                || !Boolean.TRUE.equals(root.get("ok"))) {

            log.warn(
                    "ReefAPI returned an unsuccessful response"
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ReefAPI did not return a successful response"
            );
        }

        Object dataObject = root.get("data");

        if (!(dataObject instanceof Map<?, ?> data)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unexpected ReefAPI response structure"
            );
        }

        String responseStoreCode =
                stringValue(data.get("store_id"));

        if (responseStoreCode == null
                || !responseStoreCode.equalsIgnoreCase(
                        storeCode
                )
                || Boolean.TRUE.equals(
                        data.get("store_defaulted")
                )) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ReefAPI did not confirm the requested store"
            );
        }

        Object productsObject = data.get("products");

        if (!(productsObject instanceof List<?> products)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ReefAPI response has no products array"
            );
        }

        int matchedProducts = 0;
        int savedPromotions = 0;
        int unchangedPromotions = 0;
        int skippedPromotions = 0;
        var unmatchedTitles = new LinkedHashSet<String>();

        LocalDate today = LocalDate.now(
                SOUTH_AFRICA
        );

        for (Object item : products) {
            if (!(item instanceof Map<?, ?> product)) {
                skippedPromotions++;
                continue;
            }

            String title = stringValue(
                    product.get("title")
            );

            if (title == null) {
                skippedPromotions++;
                continue;
            }

            String barcode = stringValue(product.get("barcode"));
            Long productId = barcode==null || !barcode.matches("[0-9]{8,14}")
                    ? resolveTrustedProduct(title)
                    : jdbc.queryForList("SELECT id FROM products WHERE barcode=?",Long.class,barcode)
                        .stream().findFirst().orElse(null);

            if (productId == null) {
                if (unmatchedTitles.size() < 30) unmatchedTitles.add(title.substring(0, Math.min(title.length(), 500)));
                skippedPromotions++;
                continue;
            }

            matchedProducts++;

            Object promotionsObject =
                    product.get("promotions");

            if (!(promotionsObject instanceof List<?> promos)) {
                continue;
            }

            for (Object promoObject : promos) {
                if (!(promoObject instanceof Map<?, ?> promo)) {
                    skippedPromotions++;
                    continue;
                }

                try {
                    switch (savePromotion(productId,storeId,product,promo,today)) {
                        case SAVED -> savedPromotions++;
                        case UNCHANGED -> unchangedPromotions++;
                        case SKIPPED -> skippedPromotions++;
                    }
                } catch (Exception ex) {
                    skippedPromotions++;

                    log.warn(
                            "Skipping promotion for product {}: {}",
                            productId,
                            ex.getMessage()
                    );
                }
            }
        }

        log.info(
                "ReefAPI sync: store={}, fetched={}, "
                        + "matched={}, saved={}, skipped={}",
                storeCode,
                products.size(),
                matchedProducts,
                savedPromotions,
                skippedPromotions
        );

        return Map.of(
                "message",
                "Pick n Pay promotion sync completed "
                        + "for the first page",
                "retailer",
                "picknpay",
                "storeCode",
                storeCode,
                "productsFetched",
                products.size(),
                "matchedProducts",
                matchedProducts,
                "promotionsSaved",
                savedPromotions,
                "promotionsSkipped",
                skippedPromotions,
                "promotionsUnchanged",
                unchangedPromotions,
                "unmatchedTitles", List.copyOf(unmatchedTitles)
        );
    }

    /**
     * Only product aliases explicitly matched using a
     * barcode or manual review are eligible.
     *
     * Fuzzy matching is intentionally excluded here.
     */
    private Long resolveTrustedProduct(String name) {

        List<Long> matches = jdbc.queryForList(
                """
                SELECT a.product_id
                FROM product_aliases a
                JOIN retailers r
                    ON r.id = a.retailer_id
                WHERE r.slug = 'picknpay'
                  AND a.raw_name = ?
                  AND a.confidence >= 0.95
                  AND a.matched_by IN (
                      'BARCODE',
                      'MANUAL'
                  )
                ORDER BY a.id
                LIMIT 1
                """,
                Long.class,
                name
        );

        return matches.isEmpty()
                ? null
                : matches.get(0);
    }

    /**
     * Queue dated branch offers and preserve the provider's advertised terms for review.
     */
    private ImportOutcome savePromotion(
            Long productId,
            Long storeId,
            Map<?, ?> product,
            Map<?, ?> promo,
            LocalDate today
    ) {
        boolean member = Boolean.TRUE.equals(promo.get("card_required"));

        LocalDate start = parseDate(
                promo.get("starts")
        );

        LocalDate end = parseDate(
                promo.get("ends")
        );

        if (start == null
                || end == null
                || end.isBefore(start)
                || end.isBefore(today)) {
            return ImportOutcome.SKIPPED;
        }

        String kind = stringValue(
                promo.get("kind")
        );

        String type = stringValue(
                promo.get("type")
        );

        String promoType;
        BigDecimal price;
        int minimumQuantity;

        if ("combo".equalsIgnoreCase(kind)) {
            promoType = "BUNDLE";
            price = decimal(promo.get("deal_price"));
            minimumQuantity = 1;
        } else if ("multi_buy".equalsIgnoreCase(kind)) {

            BigDecimal dealPrice = decimal(
                    promo.get("deal_price")
            );

            Integer quantity = integer(
                    promo.get("quantity")
            );

            if (!positive(dealPrice)
                    || quantity == null
                    || quantity <= 1) {
                return ImportOutcome.SKIPPED;
            }

            promoType = "MULTIBUY";
            price = dealPrice;
            minimumQuantity = quantity;

        } else if ("save".equalsIgnoreCase(type)) {

            BigDecimal currentPrice = decimal(
                    product.get("price")
            );

            BigDecimal oldPrice = decimal(
                    product.get("original_price")
            );

            if (!positive(currentPrice)
                    || !positive(oldPrice)
                    || currentPrice.compareTo(oldPrice) >= 0) {
                return ImportOutcome.SKIPPED;
            }

            promoType = member ? "MEMBER" : "DISCOUNT";
            price = currentPrice;
            minimumQuantity = 1;

        } else {
            return ImportOutcome.SKIPPED;
        }

        String description = stringValue(
                promo.get("text")
        );

        if (description == null) {
            description = stringValue(
                    promo.get("description")
            );
        }

        if (description != null
                && description.length() > 500) {
            description =
                    description.substring(0, 500);
        }

        String program = member ? "Retailer loyalty card" : null;
        String sourceKey = productId + ":" + storeId + ":" + promoType + ":" + start + ":" + end + ":" + member;
        return jdbc.update("""
                INSERT INTO promotions(product_id,store_id,promo_type,description,promo_price,min_quantity,
                    start_date,end_date,status,loyalty_program,source_type,source_reference,source_key,verification)
                VALUES (?,?,?,?,?,?,?,?,'PENDING',?,'REEFAPI',?,?, 'ADVERTISED')
                ON CONFLICT (source_type,source_key) DO UPDATE SET
                  description=excluded.description,promo_price=excluded.promo_price,
                  min_quantity=excluded.min_quantity,loyalty_program=excluded.loyalty_program,
                  status='PENDING',collected_at=now(),updated_at=now(),revision=promotions.revision+1
                WHERE promotions.status NOT IN ('REJECTED','SUSPENDED')
                  AND (promotions.description,promotions.promo_price,promotions.min_quantity,promotions.loyalty_program)
                      IS DISTINCT FROM (excluded.description,excluded.promo_price,excluded.min_quantity,excluded.loyalty_program)
                """,productId,storeId,promoType,description,price,minimumQuantity,Date.valueOf(start),
                Date.valueOf(end),program,"ReefAPI matched branch " + storeId,sourceKey) > 0 ? ImportOutcome.SAVED : ImportOutcome.UNCHANGED;
    }

    private enum ImportOutcome { SAVED,UNCHANGED,SKIPPED }

    private LocalDate parseDate(Object value) {
        String text = stringValue(value);

        if (text == null || text.length() < 10) {
            return null;
        }

        try {
            return LocalDate.parse(
                    text.substring(0, 10)
            );
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return new BigDecimal(
                    value.toString()
            );
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer integer(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Integer.valueOf(
                    value.toString()
            );
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null
                && value.signum() > 0;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }

        String text = value.toString().trim();

        return text.isEmpty() ? null : text;
    }

    private String normalizeRetailer(String value) {
        if (value == null) {
            return "";
        }

        String slug = value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");

        return switch (slug) {
            case "pnp", "picknpay" -> "picknpay";
            default -> slug;
        };
    }
}
