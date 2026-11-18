package za.co.shoppingdiary.ingestion.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import za.co.shoppingdiary.ingestion.api.dto.LoyaltyHubPrice;
import za.co.shoppingdiary.ingestion.api.dto.LoyaltyHubResponse;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyHubService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final JdbcTemplate jdbc;

    @Value("${loyaltyhub.api.key}")
    private String apiKey;

    public void syncSearchResults(String searchQuery) {
        log.info("Starting LoyaltyHub sync for query: {}", searchQuery);

        // Request up to 50 items if on a paid plan, or 10 if on the free tier.
        String url = "https://loyaltyhub.co.za/api/v1/prices?search=" + searchQuery + "&limit=50";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<LoyaltyHubResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, LoyaltyHubResponse.class);

            if (response.getBody() == null || response.getBody().getData() == null) {
                log.warn("No data returned from LoyaltyHub");
                return;
            }

            List<LoyaltyHubPrice> prices = response.getBody().getData();
            int savedCount = 0;

            for (LoyaltyHubPrice priceData : prices) {
                boolean saved = savePriceObservation(priceData);
                if (saved)
                    savedCount++;
            }

            log.info("LoyaltyHub sync complete. Saved {} new observations.", savedCount);

        } catch (Exception e) {
            log.error("Failed to sync from LoyaltyHub: {}", e.getMessage());
            throw new IllegalArgumentException("LoyaltyHub API Error: " + e.getMessage());
        }
    }

    private boolean savePriceObservation(LoyaltyHubPrice data) {
        try {
            Long storeId = getOrCreateOnlineStore(data.getRetailer());
            if (storeId == null)
                return false;

            Long productId = getOrCreateProduct(data.getName(), data.getBarcode(), data.getBrand(), data.getImageUrl(),
                    data.getCategory());
            if (productId == null)
                return false;

            // Price Logic
            BigDecimal shelfPrice = data.getPrice();
            BigDecimal promoPrice = data.getWasPrice(); // was_price implies the current price is a promo

            // Extract the numeric unit price value if it exists
            BigDecimal unitPriceValue = null;
            if (data.getUnitPrice() != null) {
                unitPriceValue = data.getUnitPrice().getValue();
            }

            Instant observedAt = data.getLastSeenAt() != null
                    ? Instant.parse(data.getLastSeenAt())
                    : Instant.now();

            String date = observedAt.toString().substring(0, 10);
            String hash = sha256(productId + "|" + storeId + "|" + shelfPrice + "|" + date);

            int rows = jdbc.update(
                    """
                            INSERT INTO price_observations
                            (product_id, store_id, source_type, source_reference, observed_at, shelf_price, promo_price, stock_status, verification_status, content_hash, unit_price)
                            VALUES (?, ?, 'API', 'LoyaltyHub', ?, ?, ?, 'IN_STOCK', 'VERIFIED', ?, ?)
                            ON CONFLICT (content_hash) DO NOTHING
                            """,
                    productId, storeId, java.sql.Timestamp.from(observedAt),
                    shelfPrice, promoPrice, hash, unitPriceValue);

            return rows > 0;
        } catch (Exception e) {
            log.warn("Skipping item due to error: {}", e.getMessage());
            return false;
        }
    }

    private Long getOrCreateOnlineStore(String retailerSlug) {
        String normalizedSlug = retailerSlug.equals("pnp") ? "picknpay" : retailerSlug;

        List<Long> ids = jdbc.queryForList(
                "SELECT s.id FROM stores s JOIN retailers r ON r.id = s.retailer_id WHERE r.slug = ? AND s.store_code = 'ONLINE'",
                Long.class, normalizedSlug);

        if (!ids.isEmpty())
            return ids.get(0);

        try {
            Long retailerId = jdbc.queryForObject("SELECT id FROM retailers WHERE slug = ?", Long.class,
                    normalizedSlug);
            jdbc.update(
                    """
                            INSERT INTO stores (retailer_id, name, store_code, location, city, store_type)
                            VALUES (?, ?, 'ONLINE', ST_SetSRID(ST_MakePoint(28.0473, -26.2041), 4326), 'Johannesburg', 'SUPERMARKET')
                            """,
                    retailerId, retailerSlug + " Online");

            return jdbc.queryForObject("SELECT id FROM stores WHERE retailer_id = ? AND store_code = 'ONLINE'",
                    Long.class, retailerId);
        } catch (Exception e) {
            log.error("Could not find or create retailer: {}", retailerSlug);
            return null;
        }
    }

    private Long getOrCreateProduct(String name, String barcode, String brand, String imageUrl, String category) {
        if (barcode != null && !barcode.isEmpty()) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM products WHERE barcode = ?", Long.class, barcode);
            if (!ids.isEmpty())
                return ids.get(0);
        }

        List<Long> nameIds = jdbc.queryForList("SELECT id FROM products WHERE canonical_name = ?", Long.class, name);
        if (!nameIds.isEmpty())
            return nameIds.get(0);

        jdbc.update("INSERT INTO products (canonical_name, barcode, brand, image_url, category) VALUES (?, ?, ?, ?, ?)",
                name, barcode, brand, imageUrl, category);
        return jdbc.queryForObject("SELECT id FROM products WHERE canonical_name = ?", Long.class, name);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}