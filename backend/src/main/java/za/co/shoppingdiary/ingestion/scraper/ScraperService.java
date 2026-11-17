package za.co.shoppingdiary.ingestion.scraper;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ScraperService {

    private final List<RetailerScraper> scrapers;

    public void runScraper(String retailerSlug, Long storeId, String htmlContent) {
        // 1. Find the right scraper for the job
        RetailerScraper scraper = scrapers.stream()
                .filter(s -> s.getRetailerSlug().equalsIgnoreCase(retailerSlug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No scraper found for " + retailerSlug));

        // 2. Parse the HTML
        Document doc = Jsoup.parse(htmlContent);

        // 3. Execute
        scraper.scrapeAndSave(doc, storeId);
    }
}