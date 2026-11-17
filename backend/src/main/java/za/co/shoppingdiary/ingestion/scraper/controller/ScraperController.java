package za.co.shoppingdiary.ingestion.scraper.controller;

import lombok.RequiredArgsConstructor;
import za.co.shoppingdiary.ingestion.scraper.service.ScraperService;

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