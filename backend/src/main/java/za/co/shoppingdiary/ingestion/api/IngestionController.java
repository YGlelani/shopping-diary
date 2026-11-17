package za.co.shoppingdiary.ingestion.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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