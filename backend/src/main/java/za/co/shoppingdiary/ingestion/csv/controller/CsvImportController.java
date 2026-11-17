package za.co.shoppingdiary.ingestion.csv.controller; // <-- Fixed package

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import za.co.shoppingdiary.ingestion.csv.dto.ImportResult; // <-- Fixed import
import za.co.shoppingdiary.ingestion.csv.service.CsvImportService; // <-- Fixed import
import za.co.shoppingdiary.ingestion.csv.service.HdxImportService; // <-- Fixed import

@RestController
@RequestMapping("/api/v1/admin/import")
@RequiredArgsConstructor
public class CsvImportController {

    private final CsvImportService csvImportService;
    private final HdxImportService hdxImportService;

    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return csvImportService.importFile(file);
    }

    @PostMapping(value = "/hdx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importHdx(@RequestParam("file") MultipartFile file) {
        return hdxImportService.importHdxCsv(file);
    }
}