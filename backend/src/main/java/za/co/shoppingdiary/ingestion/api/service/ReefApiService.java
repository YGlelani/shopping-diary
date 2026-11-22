
package za.co.shoppingdiary.ingestion.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import za.co.shoppingdiary.ingestion.api.dto.ReefApiProduct;
import za.co.shoppingdiary.ingestion.api.dto.ReefApiResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReefApiService {

    private static final String API_URL =
            "https://api.reefapi.com/v1/promotions";

    private final JdbcTemplate jdbc;

    private final RestTemplate restTemplate =
            new RestTemplate();

    @Value("${reefapi.api.key:}")
    private String apiKey;

    public void syncPromotions(String retailerSlug) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "REEFAPI_KEY is not configured"
            );
        }

        if (retailerSlug == null
                || retailerSlug.isBlank()) {
            throw new IllegalArgumentException(
                    "Retailer is required"
            );
        }

        String retailer = normalizeRetailer(
                retailerSlug
        );

        String url = UriComponentsBuilder
                .fromUriString(API_URL)
                .queryParam("retailer", retailer)
                .encode()
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.setAccept(
                List.of(MediaType.APPLICATION_JSON)
        );

        try {
            ResponseEntity<ReefApiResponse> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            new HttpEntity<Void>(headers),
                            ReefApiResponse.class
                    );

            ReefApiResponse body = response.getBody();

            if (body == null
                    || body.getData() == null) {
                log.warn("No ReefAPI promotions returned");
                return;
            }

            int saved = 0;
            int skipped = 0;

            for (ReefApiProduct product :
                    body.getData()) {

                try {
                    if (savePromotion(product, retailer)) {
                        saved++;
                    } else {
                        skipped++;
                    }
                } catch (Exception ex) {
                    skipped++;
                    log.warn(
                            "Skipping promotion: {}",
                            ex.getMessage()
                    );
                }
            }

            log.info(
                    "ReefAPI import: saved={}, skipped={}",
                    saved,
                    skipped
            );

        } catch (RestClientException ex) {
            throw new IllegalStateException(
                    "ReefAPI request failed",
                    ex
            );
        }
    }

    private boolean savePromotion(
            ReefApiProduct promo,
            String retailerSlug
    ) {
        if (promo == null
                || blank(promo.getBarcode())
                || blank(promo.getStoreId())
                || !positive(promo.getPromoPrice())
                || blank(promo.getStartDate())
                || blank(promo.getEndDate())) {
            return false;
        }

        LocalDate start;
        LocalDate end;

        try {
            start = LocalDate.parse(
                    promo.getStartDate()
            );
            end = LocalDate.parse(
                    promo.getEndDate()
            );
        } catch (DateTimeParseException ex) {
            return false;
        }

        if (end.isBefore(start)) {
            return false;
        }

        if (end.isBefore(LocalDate.now())) {
            return false;
        }

        List<Long> productIds =
                jdbc.queryForList(
                        """
                        SELECT id
                        FROM products
                        WHERE barcode = ?
                        LIMIT 1
                        """,
                        Long.class,
                        promo.getBarcode().trim()
                );

        if (productIds.isEmpty()) {
            return false;
        }

        List<Long> storeIds =
                jdbc.queryForList(
                        """
                        SELECT s.id
                        FROM stores s
                        JOIN retailers r
                            ON r.id = s.retailer_id
                        WHERE r.slug = ?
                          AND r.active = TRUE
                          AND s.store_code = ?
                          AND s.active = TRUE
                        LIMIT 1
                        """,
                        Long.class,
                        retailerSlug,
                        promo.getStoreId().trim()
                );

        if (storeIds.isEmpty()) {
            log.debug(
                    "No verified store match for ReefAPI store {}",
                    promo.getStoreId()
            );
            return false;
        }

        Long productId = productIds.get(0);
        Long storeId = storeIds.get(0);

        int minQuantity =
                promo.getMinQuantity() == null
                        ? 1
                        : promo.getMinQuantity();

        if (minQuantity <= 0) {
            return false;
        }

        String type = minQuantity > 1
                ? "MULTIBUY"
                : "DISCOUNT";

        String description =
                promo.getPromoDescription();

        if (description != null
                && description.length() > 500) {
            description =
                    description.substring(0, 500);
        }

        String status =
                start.isAfter(LocalDate.now())
                        ? "SCHEDULED"
                        : "ACTIVE";

        // The current schema does not have a unique
        // (product_id, store_id, start_date) constraint.
        // Use an explicit lookup instead of invalid
        // ON CONFLICT syntax.

        List<Long> existing =
                jdbc.queryForList(
                        """
                        SELECT id
                        FROM promotions
                        WHERE product_id = ?
                          AND store_id = ?
                          AND promo_type = ?
                          AND start_date = ?
                          AND end_date = ?
                        ORDER BY id
                        LIMIT 1
                        """,
                        Long.class,
                        productId,
                        storeId,
                        type,
                        java.sql.Date.valueOf(start),
                        java.sql.Date.valueOf(end)
                );

        if (!existing.isEmpty()) {
            jdbc.update(
                    """
                    UPDATE promotions
                    SET promo_price = ?,
                        min_quantity = ?,
                        description = ?,
                        status = ?
                    WHERE id = ?
                    """,
                    promo.getPromoPrice(),
                    minQuantity,
                    description,
                    status,
                    existing.get(0)
            );

            return true;
        }

        int inserted = jdbc.update(
                """
                INSERT INTO promotions (
                    product_id,
                    store_id,
                    promo_type,
                    description,
                    promo_price,
                    min_quantity,
                    start_date,
                    end_date,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                productId,
                storeId,
                type,
                description,
                promo.getPromoPrice(),
                minQuantity,
                java.sql.Date.valueOf(start),
                java.sql.Date.valueOf(end),
                status
        );

        return inserted > 0;
    }

    private String normalizeRetailer(
            String input
    ) {
        String normalized = input
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");

        return switch (normalized) {
            case "pnp", "picknpay" -> "picknpay";
            case "checkers" -> "checkers";
            case "shoprite" -> "shoprite";
            case "woolworths" -> "woolworths";
            case "spar" -> "spar";
            case "flm", "foodloversmarket" -> "flm";
            default -> normalized;
        };
    }

    private boolean positive(BigDecimal value) {
        return value != null
                && value.signum() > 0;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
