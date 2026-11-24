
package za.co.shoppingdiary.ingestion.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.ingestion.api.service.HdxDownloadService;
import za.co.shoppingdiary.ingestion.api.service.LoyaltyHubService;
import za.co.shoppingdiary.promotion.SpecialsImportService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ingest")
@RequiredArgsConstructor
public class IngestionController {

    private final LoyaltyHubService loyaltyHubService;
    private final SpecialsImportService specialsImport;
    private final HdxDownloadService hdxDownloadService;

    @PostMapping("/loyaltyhub")
    public Map<String, String> triggerLoyaltyHubSync(
            @RequestParam String query
    ) {
        loyaltyHubService.syncSearchResults(query);

        return Map.of(
                "message",
                "LoyaltyHub sync completed for: " + query
        );
    }

    @PostMapping("/reefapi/promotions")
    public Map<String, Object> triggerReefApiSync(
            @RequestParam String retailerSlug,
            @RequestParam(required = false) String storeCode
    ) {
        return specialsImport.collect(
                retailerSlug,
                storeCode
        );
    }

    @PostMapping("/hdx/trigger")
    public Map<String, String> triggerHdxSync() {
        hdxDownloadService.downloadAndImportHdxData();

        return Map.of(
                "message",
                "HDX download and import completed."
        );
    }
}
