
package za.co.shoppingdiary.ingestion.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import za.co.shoppingdiary.ingestion.api.service.OpenFoodFactsService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(
    "/api/v1/admin/catalogue/open-food-facts"
)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class OpenFoodFactsController {

    private final OpenFoodFactsService service;

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() {
        return Map.of("importEnabled", service.isWriteEnabled());
    }

    @GetMapping("/search")
    public List<OpenFoodFactsService.Candidate> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return service.search(query, limit);
    }

    @GetMapping("/preview/{barcode}")
    public OpenFoodFactsService.Candidate preview(
            @PathVariable String barcode
    ) {
        return service.previewBarcode(barcode);
    }

    // Preserve the original one-product endpoint.
    @PostMapping("/import/{barcode}")
    public OpenFoodFactsService.ImportResult importSingle(
            @PathVariable String barcode,

            @RequestParam(defaultValue = "true")
            boolean dryRun,

            @RequestParam(defaultValue = "false")
            boolean confirm
    ) {
        OpenFoodFactsService.Candidate candidate =
                service.previewBarcode(barcode);

        OpenFoodFactsService.BulkResult result =
                service.bulkImport(
                    new OpenFoodFactsService.BulkRequest(
                            List.of(barcode),
                            dryRun,
                            confirm
                    )
                );

        OpenFoodFactsService.Item item =
                result.items().get(0);

        return new OpenFoodFactsService.ImportResult(
                dryRun,
                item.status(),
                item.productId(),
                candidate
        );
    }

    // New bulk preview and import endpoint.
    @PostMapping("/bulk")
    public OpenFoodFactsService.BulkResult bulk(
            @RequestBody
            OpenFoodFactsService.BulkRequest request
    ) {
        return service.bulkImport(request);
    }
}
