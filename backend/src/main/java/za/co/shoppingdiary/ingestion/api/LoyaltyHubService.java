package za.co.shoppingdiary.ingestion.api;

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
import java.time.format.DateTimeFormatter;
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

        String url = "https://loyaltyhub.co.za/api/v1/prices?search=" + searchQuery + "&limit=10"; // 10 rows per page on free tier

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
                if (saved) savedCount++;
            }

            log.info("LoyaltyHub sync complete. Saved {} new observations.", savedCount);

        } catch (Exception e) {
            log.error("Failed to sync from LoyaltyHub: {}", e.getMessage());
        }
    }

    private boolean savePriceObservation(LoyaltyHubPrice data) {
        try {
            // 1. Resolve the Store (We map the retailer to an "Online" store)
            Long storeId = getOrCreateOnlineStore(data.getRetailer());
            if (storeId == null) return false;

            // 2. Resolve the Product (Match by barcode, then name)
            Long productId = getOrCreateProduct(data.getName(), data.getBarcode(), data.getBrand());
            if (productId == null) return false;

            // 3. Prepare price and dates
            BigDecimal shelfPrice = data.getPrice();
            BigDecimal promoPrice = data.getWasPrice(); // If wasPrice exists, the current price is a promo
            
            // Convert ISO string (e.g. "2026-11-10T08:40:05Z") to Instant
            Instant observedAt = data.getLastSeenAt() != null 
                    ? Instant.parse(data.getLastSeenAt()) 
                    : Instant.now();

            // 4. Generate unique hash
            String date = observedAt.toString().substring(0, 10); // Just the YYYY-MM-DD part
            String hash = sha256(productId + "|" + storeId + "|" + shelfPrice + "|" + date);

            // 5. Insert
            int rows = jdbc.update("""
                INSERT INTO price_observations
                (product_id, store_id, source_type, source_reference, observed_at, shelf_price, promo_price, stock_status, verification_status, content_hash, unit_price)
                VALUES (?, ?, 'API', 'LoyaltyHub', ?, ?, ?, 'IN_STOCK', 'VERIFIED', ?, ?)
                ON CONFLICT (content_hash) DO NOTHING
                """,
                productId, storeId, java.sql.Timestamp.from(observedAt), 
                shelfPrice, promoPrice, hash,
                data.getUnitPrice() != null ? data.getUnitPrice().getValue() : null
            );

            return rows > 0;
        } catch (Exception e) {
            log.warn("Skipping item due to error: {}", e.getMessage());
            return false;
        }
    }

    private Long getOrCreateOnlineStore(String retailerSlug) {
        // Normalize slugs (LoyaltyHub uses "shoprite", "pnp", etc.)
        String normalizedSlug = retailerSlug.equals("pnp") ? "picknpay" : retailerSlug;

        // Check if we have an "Online" store for this retailer
        List<Long> ids = jdbc.queryForList(
            "SELECT s.id FROM stores s JOIN retailers r ON r.id = s.retailer_id WHERE r.slug = ? AND s.store_code = 'ONLINE'",
            Long.class, normalizedSlug);

        if (!ids.isEmpty()) return ids.get(0);

        // If not, create it (using dummy coordinates in SA)
        try {
            Long retailerId = jdbc.queryForObject("SELECT id FROM retailers WHERE slug = ?", Long.class, normalizedSlug);
            jdbc.update("""
                INSERT INTO stores (retailer_id, name, store_code, location, city, store_type)
                VALUES (?, ?, 'ONLINE', ST_SetSRID(ST_MakePoint(28.0473, -26.2041), 4326), 'Johannesburg', 'SUPERMARKET')
                """, retailerId, retailerSlug + " Online");
            
            return jdbc.queryForObject("SELECT id FROM stores WHERE retailer_id = ? AND store_code = 'ONLINE'", Long.class, retailerId);
        } catch (Exception e) {
            log.error("Could not find or create retailer: {}", retailerSlug);
            return null;
        }
    }

    private Long getOrCreateProduct(String name, String barcode, String brand) {
        if (barcode != null && !barcode.isEmpty()) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM products WHERE barcode = ?", Long.class, barcode);
            if (!ids.isEmpty()) return ids.get(0);
        }
        
        List<Long> nameIds = jdbc.queryForList("SELECT id FROM products WHERE canonical_name = ?", Long.class, name);
        if (!nameIds.isEmpty()) return nameIds.get(0);

        jdbc.update("INSERT INTO products (canonical_name, barcode, brand) VALUES (?, ?, ?)", name, barcode, brand);
        return jdbc.queryForObject("SELECT id FROM products WHERE canonical_name = ?", Long.class, name);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}