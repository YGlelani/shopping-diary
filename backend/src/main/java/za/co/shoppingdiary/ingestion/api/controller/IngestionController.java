package za.co.shoppingdiary.ingestion.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.ingestion.api.service.LoyaltyHubService;
import za.co.shoppingdiary.ingestion.api.service.ReefApiService;

@RestController
@RequestMapping("/api/v1/admin/ingest")
@RequiredArgsConstructor
public class IngestionController {

    private final LoyaltyHubService loyaltyHubService;
    private final ReefApiService reefApiService; // <-- Add this

    @PostMapping("/loyaltyhub")
    public String triggerLoyaltyHubSync(@RequestParam String query) {
        loyaltyHubService.syncSearchResults(query);
        return "Sync triggered for query: " + query;
    }

    @PostMapping("/reefapi/promotions")
    public String triggerReefApiSync(@RequestParam String retailerSlug) {
        reefApiService.syncPromotions(retailerSlug);
        return "Promotion sync triggered for: " + retailerSlug;
    }
}