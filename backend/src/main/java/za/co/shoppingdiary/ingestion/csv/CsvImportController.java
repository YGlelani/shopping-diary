package za.co.shoppingdiary.ingestion.csv;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/import")
@RequiredArgsConstructor
public class CsvImportController {

    private final CsvImportService service;

    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return service.importFile(file);
    }
}