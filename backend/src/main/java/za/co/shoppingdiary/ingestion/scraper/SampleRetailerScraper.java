package za.co.shoppingdiary.ingestion.scraper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Slf4j
@Component
@RequiredArgsConstructor
public class SampleRetailerScraper implements RetailerScraper {

    private final JdbcTemplate jdbc;

    @Override
    public String getRetailerSlug() {
        return "checkers"; // Target Checkers
    }

    @Override
    public void scrapeAndSave(Document document, Long storeId) {
        // Assume the HTML looks like: <div class="product-card" data-id="1" data-price="29.99">
        Elements products = document.select("div.product-card");
        
        for (Element product : products) {
            try {
                String rawName = product.select("h3.product-name").text();
                String priceStr = product.attr("data-price");
                String barcode = product.attr("data-barcode");
                
                if (priceStr.isEmpty() || rawName.isEmpty()) continue;

                BigDecimal price = new BigDecimal(priceStr);
                
                // 1. Find or create the product in our DB (Simplified matching logic)
                Long productId = findOrCreateProduct(rawName, barcode);
                if (productId == null) continue;

                // 2. Generate the hash to prevent duplicates
                String date = java.time.LocalDate.now().toString();
                String hash = sha256(productId + "|" + storeId + "|" + price + "|" + date);

                // 3. Insert the Price Observation
                jdbc.update("""
                    INSERT INTO price_observations 
                    (product_id, store_id, source_type, observed_at, shelf_price, stock_status, verification_status, content_hash)
                    VALUES (?, ?, 'SCRAPER', ?, ?, 'IN_STOCK', 'UNVERIFIED', ?)
                    ON CONFLICT (content_hash) DO NOTHING
                    """,
                    productId, storeId, java.sql.Timestamp.from(Instant.now()), price, hash
                );
                
                log.info("Scraped: {} for R{}", rawName, price);

            } catch (Exception e) {
                log.error("Failed to parse product block", e);
            }
        }
    }

    private Long findOrCreateProduct(String name, String barcode) {
        // Try to find by barcode first
        if (!barcode.isEmpty()) {
            var ids = jdbc.queryForList("SELECT id FROM products WHERE barcode = ?", Long.class, barcode);
            if (!ids.isEmpty()) return ids.get(0);
        }
        
        // Try by exact name
        var nameIds = jdbc.queryForList("SELECT id FROM products WHERE canonical_name = ?", Long.class, name);
        if (!nameIds.isEmpty()) return nameIds.get(0);

        // Create new product
        jdbc.update("INSERT INTO products (canonical_name, barcode) VALUES (?, ?)", name, barcode.isEmpty() ? null : barcode);
        return jdbc.queryForObject("SELECT id FROM products WHERE canonical_name = ?", Long.class, name);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}