package za.co.shoppingdiary.ingestion.api.controller; // <-- Fixed package

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.ingestion.api.service.LoyaltyHubService; // <-- Fixed import

@RestController
@RequestMapping("/api/v1/admin/ingest")
@RequiredArgsConstructor
public class IngestionController {

    private final LoyaltyHubService loyaltyHubService;

    @PostMapping("/loyaltyhub")
    public String triggerLoyaltyHubSync(@RequestParam String query) {
        loyaltyHubService.syncSearchResults(query);
        return "Sync triggered for query: " + query;
    }
}