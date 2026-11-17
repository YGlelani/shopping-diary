package za.co.shoppingdiary.ingestion.scraper;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/scraper")
@RequiredArgsConstructor
public class ScraperController {

    private final ScraperService scraperService;

    @PostMapping("/run")
    public String runManualScrape(
            @RequestParam String retailerSlug,
            @RequestParam Long storeId,
            @RequestBody String htmlContent) {
        
        scraperService.runScraper(retailerSlug, storeId, htmlContent);
        return "Scraping job completed successfully.";
    }
}