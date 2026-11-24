package za.co.shoppingdiary.ingestion.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/grocery-automation")
@RequiredArgsConstructor
public class GroceryAutomationController {
    private final GroceryAutomation service;

    @GetMapping
    public Map<String,Object> status() {
        return Map.of("sources",service.sources(),"runs",service.history(),"review",service.reviews(),"quota",service.quota());
    }

    @PostMapping("/{source}/sync")
    public GroceryAutomation.Run sync(@PathVariable String source,@RequestParam(required=false) String query,
                                      @RequestParam(defaultValue="true") boolean dryRun) {
        if (BulkGroceryFiles.CATALOGUE.equals(source) || BulkGroceryFiles.BRANCHES.equals(source)) return service.configuredFile(source,dryRun);
        return service.price(source,query,dryRun);
    }

    @PostMapping("/{source}/file")
    public GroceryAutomation.Run upload(@PathVariable String source,@RequestParam MultipartFile file,
                                        @RequestParam(defaultValue="true") boolean dryRun) throws IOException {
        if (!List.of(BulkGroceryFiles.CATALOGUE,BulkGroceryFiles.BRANCHES).contains(source) ||
                file.isEmpty() || file.getOriginalFilename()==null) throw new IllegalArgumentException("Choose a supported source export");
        try (var input=file.getInputStream()) {
            return service.file(source,input,file.getOriginalFilename(),dryRun);
        }
    }

    @PostMapping("/loyaltyhub/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@RequestParam String confirmation) {
        if (!"DELETE LOYALTY HUB DATA".equals(confirmation)) throw new IllegalArgumentException("Explicit confirmation is required");
        service.purgeLoyalty();
    }
}
