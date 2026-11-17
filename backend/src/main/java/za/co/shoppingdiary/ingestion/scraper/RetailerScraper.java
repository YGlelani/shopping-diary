package za.co.shoppingdiary.ingestion.scraper;

import org.jsoup.nodes.Document;

public interface RetailerScraper {
    /**
     * Returns the exact slug of the retailer (e.g., "checkers", "woolworths")
     */
    String getRetailerSlug();

    /**
     * Parses the HTML document and extracts the product data.
     */
    void scrapeAndSave(Document document, Long storeId);
}