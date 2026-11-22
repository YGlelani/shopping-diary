
package za.co.shoppingdiary.ingestion.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import za.co.shoppingdiary.ingestion.api.dto.LoyaltyHubPrice;
import za.co.shoppingdiary.ingestion.api.dto.LoyaltyHubResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyHubService {

    private static final String API_URL =
            "https://loyaltyhub.co.za/api/v1/prices";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final RestTemplate restTemplate =
            new RestTemplate();

    @Value("${loyaltyhub.api.key:}")
    private String apiKey;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxPriceAgeDays;

    public void syncSearchResults(String searchQuery) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "LOYALTYHUB_API_KEY is not configured"
            );
        }

        if (searchQuery == null
                || searchQuery.isBlank()) {
            throw new IllegalArgumentException(
                    "Search query is required"
            );
        }

        String query = searchQuery.trim();

        if (query.length() > 200) {
            throw new IllegalArgumentException(
                    "Search query is too long"
            );
        }

        String url = UriComponentsBuilder
                .fromUriString(API_URL)
                .queryParam("search", query)
                .queryParam("limit", 50)
                .encode()
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setAccept(
                List.of(MediaType.APPLICATION_JSON)
        );

        LoyaltyHubResponse body;

        try {
            ResponseEntity<LoyaltyHubResponse> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            new HttpEntity<Void>(headers),
                            LoyaltyHubResponse.class
                    );

            body = response.getBody();

        } catch (RestClientException ex) {
            throw new IllegalStateException(
                    "LoyaltyHub API request failed",
                    ex
            );
        }

        if (body == null || body.getData() == null) {
            log.info("LoyaltyHub returned no data");
            return;
        }

        int saved = 0;
        int skipped = 0;

        for (LoyaltyHubPrice price : body.getData()) {
            try {
                if (savePriceObservation(price)) {
                    saved++;
                } else {
                    skipped++;
                }
            } catch (Exception ex) {
                skipped++;
                log.warn(
                        "Skipping LoyaltyHub item: {}",
                        ex.getMessage()
                );
            }
        }

        log.info(
                "LoyaltyHub sync finished: saved={}, skipped={}",
                saved,
                skipped
        );
    }

    private boolean savePriceObservation(
            LoyaltyHubPrice data
    ) {
        if (data == null
                || blank(data.getName())
                || blank(data.getRetailer())
                || !positive(data.getPrice())) {
            return false;
        }

        if (data.getName().trim().length() > 255) {
            return false;
        }

        String currency = data.getCurrency();

        if (!blank(currency)
                && !currency.trim()
                        .equalsIgnoreCase("ZAR")) {
            return false;
        }

        Instant observedAt =
                parseTimestamp(data.getLastSeenAt());

        if (observedAt == null) {
            return false;
        }

        Instant now = Instant.now();

        if (observedAt.isAfter(now)
                || observedAt.isBefore(
                        now.minus(
                                Duration.ofDays(
                                        maxPriceAgeDays
                                )
                        )
                )) {
            return false;
        }

        String retailerSlug = normalizeRetailer(
                data.getRetailer()
        );

        Long storeId =
                getOrCreateOnlineStore(retailerSlug);

        if (storeId == null) {
            return false;
        }

        Long productId = getOrCreateProduct(data);

        if (productId == null) {
            return false;
        }

        // Current public price is the provider's price.
        BigDecimal shelfPrice = data.getPrice();
        BigDecimal promoPrice = null;

        // was_price is the old regular price.
        if (Boolean.TRUE.equals(
                data.getOnPromotion())
                && positive(data.getWasPrice())
                && data.getWasPrice()
                        .compareTo(data.getPrice()) > 0) {

            shelfPrice = data.getWasPrice();
            promoPrice = data.getPrice();
        }

        BigDecimal memberPrice =
                positive(data.getLoyaltyPrice())
                        ? data.getLoyaltyPrice()
                        : null;

        BigDecimal unitPrice =
                data.getUnitPrice() != null
                && positive(
                        data.getUnitPrice().getValue()
                )
                        ? data.getUnitPrice().getValue()
                        : null;

        String stockStatus =
                data.getInStock() == null
                        ? "UNKNOWN"
                        : data.getInStock()
                            ? "IN_STOCK"
                            : "OUT_OF_STOCK";

        // Imported prices are not independently verified.
        String verification = "UNVERIFIED";

        String contentHash = sha256(
                productId + "|" +
                storeId + "|" +
                observedAt + "|" +
                shelfPrice + "|" +
                promoPrice + "|" +
                memberPrice + "|" +
                stockStatus
        );

        String rawPayload = serialize(data);

        int inserted = jdbc.update(
                """
                INSERT INTO price_observations (
                    product_id,
                    store_id,
                    source_type,
                    source_reference,
                    observed_at,
                    shelf_price,
                    promo_price,
                    member_price,
                    unit_price,
                    stock_status,
                    verification_status,
                    content_hash,
                    raw_payload
                )
                VALUES (
                    ?, ?, 'API', 'LoyaltyHub',
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS jsonb)
                )
                ON CONFLICT (content_hash)
                DO NOTHING
                """,
                productId,
                storeId,
                Timestamp.from(observedAt),
                shelfPrice,
                promoPrice,
                memberPrice,
                unitPrice,
                stockStatus,
                verification,
                contentHash,
                rawPayload
        );

        return inserted > 0;
    }

    private Long getOrCreateOnlineStore(
            String retailerSlug
    ) {
        List<Long> retailerIds =
                jdbc.queryForList(
                        """
                        SELECT id
                        FROM retailers
                        WHERE slug = ?
                          AND active = TRUE
                        LIMIT 1
                        """,
                        Long.class,
                        retailerSlug
                );

        if (retailerIds.isEmpty()) {
            log.warn(
                    "LoyaltyHub retailer is not mapped: {}",
                    retailerSlug
            );
            return null;
        }

        Long retailerId = retailerIds.get(0);

        // A retailer-wide online price is not evidence
        // of an offer at a physical branch.
        // Use an inactive ONLINE record until a separate
        // online-offer model exists.

        return jdbc.queryForObject(
                """
                INSERT INTO stores (
                    retailer_id,
                    name,
                    store_code,
                    location,
                    store_type,
                    active
                )
                VALUES (
                    ?,
                    ?,
                    'ONLINE',
                    ST_SetSRID(
                        ST_MakePoint(0, 0),
                        4326
                    ),
                    'SUPERMARKET',
                    FALSE
                )
                ON CONFLICT (
                    retailer_id,
                    store_code
                )
                DO UPDATE
                SET active = FALSE
                RETURNING id
                """,
                Long.class,
                retailerId,
                retailerSlug + " Online"
        );
    }

    private Long getOrCreateProduct(
            LoyaltyHubPrice data
    ) {
        String name = data.getName().trim();
        String barcode = clean(data.getBarcode());
        String brand = clean(data.getBrand());

        String imageUrl = clean(
                data.getImageUrl()
        );

        String category = clean(
                data.getCategory()
        );

        if (barcode != null
                && barcode.length() > 20) {
            return null;
        }

        if (brand != null && brand.length() > 100) {
            brand = brand.substring(0, 100);
        }

        if (category != null
                && category.length() > 100) {
            category = category.substring(0, 100);
        }

        if (imageUrl != null
                && imageUrl.length() > 1000) {
            imageUrl = null;
        }

        if (barcode != null) {
            List<Long> existing =
                    jdbc.queryForList(
                            """
                            SELECT id
                            FROM products
                            WHERE barcode = ?
                            LIMIT 1
                            """,
                            Long.class,
                            barcode
                    );

            if (!existing.isEmpty()) {
                return existing.get(0);
            }
        }

        List<Long> matchingProducts =
                jdbc.queryForList(
                        """
                        SELECT id
                        FROM products
                        WHERE lower(canonical_name) =
                              lower(?)
                          AND lower(coalesce(brand, '')) =
                              lower(coalesce(?, ''))
                          AND (
                              barcode IS NULL
                              OR barcode = ?
                          )
                        ORDER BY id
                        LIMIT 1
                        """,
                        Long.class,
                        name,
                        brand,
                        barcode
                );

        if (!matchingProducts.isEmpty()) {
            return matchingProducts.get(0);
        }

        return jdbc.queryForObject(
                """
                INSERT INTO products (
                    canonical_name,
                    barcode,
                    brand,
                    image_url,
                    category
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (barcode)
                DO UPDATE SET
                    updated_at = products.updated_at
                RETURNING id
                """,
                Long.class,
                name,
                barcode,
                brand,
                imageUrl,
                category
        );
    }

    private String normalizeRetailer(String input) {
        String value = input
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");

        return switch (value) {
            case "pnp", "picknpay" -> "picknpay";
            case "checkers" -> "checkers";
            case "shoprite" -> "shoprite";
            case "woolworths" -> "woolworths";
            case "spar" -> "spar";
            case "flm", "foodloversmarket" -> "flm";
            default -> value;
        };
    }

    private Instant parseTimestamp(String input) {
        if (blank(input)) {
            return null;
        }

        try {
            return Instant.parse(input.trim());
        } catch (DateTimeParseException ignored) {
        }

        try {
            return OffsetDateTime.parse(
                    input.trim()
            ).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String serialize(LoyaltyHubPrice data) {
        try {
            return objectMapper.writeValueAsString(
                    data
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Unable to serialize price observation",
                    ex
            );
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            return HexFormat.of().formatHex(
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );

        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    ex
            );
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }

        String cleaned = value.trim();

        return cleaned.isEmpty() ? null : cleaned;
    }
}
