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
import za.co.shoppingdiary.ingestion.api.dto.ReefApiProduct;
import za.co.shoppingdiary.ingestion.api.dto.ReefApiResponse;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReefApiService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final JdbcTemplate jdbc;

    @Value("${reefapi.api.key}")
    private String apiKey;

    public void syncPromotions(String retailerSlug) {
        log.info("Starting ReefAPI promotion sync for: {}", retailerSlug);

        // Example endpoint (Replace with actual ReefAPI endpoint from their docs)
        String url = "https://api.reefapi.com/v1/promotions?retailer=" + retailerSlug;

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<ReefApiResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, ReefApiResponse.class);

            if (response.getBody() == null || response.getBody().getData() == null) {
                log.warn("No promotions returned from ReefAPI");
                return;
            }

            List<ReefApiProduct> promos = response.getBody().getData();
            int savedCount = 0;

            for (ReefApiProduct promo : promos) {
                if (savePromotion(promo, retailerSlug)) savedCount++;
            }

            log.info("ReefAPI sync complete. Saved {} active promotions.", savedCount);

        } catch (Exception e) {
            log.error("Failed to sync from ReefAPI: {}", e.getMessage());
        }
    }

    private boolean savePromotion(ReefApiProduct promo, String retailerSlug) {
        try {
            // 1. Resolve Product by Barcode
            List<Long> productIds = jdbc.queryForList(
                "SELECT id FROM products WHERE barcode = ?", Long.class, promo.getBarcode());
            
            if (productIds.isEmpty()) return false; // We don't track this product yet
            Long productId = productIds.get(0);

            // 2. Resolve Store (Usually an "Online" or specific store)
            List<Long> storeIds = jdbc.queryForList(
                "SELECT s.id FROM stores s JOIN retailers r ON r.id = s.retailer_id WHERE r.slug = ? LIMIT 1",
                Long.class, retailerSlug);
            
            if (storeIds.isEmpty()) return false;
            Long storeId = storeIds.get(0);

            // 3. Parse Dates (fallback to today + 7 days if API doesn't provide)
            LocalDate startDate = promo.getStartDate() != null ? LocalDate.parse(promo.getStartDate()) : LocalDate.now();
            LocalDate endDate = promo.getEndDate() != null ? LocalDate.parse(promo.getEndDate()) : LocalDate.now().plusDays(7);

            // 4. Insert or Update Promotion
            // We use ON CONFLICT to update the price if the promotion already exists
            jdbc.update("""
                INSERT INTO promotions 
                (product_id, store_id, promo_type, description, promo_price, min_quantity, start_date, end_date, status)
                VALUES (?, ?, 'MULTIBUY', ?, ?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT (product_id, store_id, start_date) 
                DO UPDATE SET promo_price = EXCLUDED.promo_price, status = 'ACTIVE'
                """,
                productId, storeId, promo.getPromoDescription(), promo.getPromoPrice(),
                promo.getMinQuantity() != null ? promo.getMinQuantity() : 1, 
                java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate)
            );

            return true;
        } catch (Exception e) {
            log.warn("Skipping promo due to error: {}", e.getMessage());
            return false;
        }
    }
}