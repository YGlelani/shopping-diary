package za.co.shoppingdiary.ingestion.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.ingestion.api.service.HdxDownloadService;
import za.co.shoppingdiary.ingestion.api.service.LoyaltyHubService;
import za.co.shoppingdiary.ingestion.api.service.ReefApiService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ingest")
@RequiredArgsConstructor
public class IngestionController {

    private final LoyaltyHubService loyaltyHubService;
    private final ReefApiService reefApiService;
    private final HdxDownloadService hdxDownloadService;

    @PostMapping("/loyaltyhub")
    public Map<String, String> triggerLoyaltyHubSync(@RequestParam String query) {
        loyaltyHubService.syncSearchResults(query);
        return Map.of("message", "Sync triggered for query: " + query);
    }

    @PostMapping("/reefapi/promotions")
    public Map<String, String> triggerReefApiSync(@RequestParam String retailerSlug) {
        reefApiService.syncPromotions(retailerSlug);
        return Map.of("message", "Promotion sync triggered for: " + retailerSlug);
    }

    @PostMapping("/hdx/trigger")
    public Map<String, String> triggerHdxSync() {
        hdxDownloadService.downloadAndImportHdxData();
        return Map.of("message", "HDX download and import triggered.");
    }
}