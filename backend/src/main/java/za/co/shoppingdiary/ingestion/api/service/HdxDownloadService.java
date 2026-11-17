package za.co.shoppingdiary.ingestion.api.service; // <-- Fixed package

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import za.co.shoppingdiary.ingestion.csv.service.HdxImportService; // <-- Fixed import
import za.co.shoppingdiary.ingestion.csv.dto.ImportResult; // <-- Fixed import

@Slf4j
@Service
@RequiredArgsConstructor
public class HdxDownloadService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final HdxImportService hdxImportService;

    private static final String HDX_CSV_URL =
        "https://data.humdata.org/dataset/279cf561-64db-4bef-a9dd-077af3c5651a/resource/63910cdd-51a0-4244-94b6-0115d615aad2/download/hdx_hapi_food_price_zaf.csv";

    @Scheduled(cron = "0 0 3 * * MON")
    public void downloadAndImportHdxData() {
        log.info("Starting automated HDX price import...");
        try {
            Resource csvResource = restTemplate.getForObject(HDX_CSV_URL, Resource.class);

            if (csvResource == null || !csvResource.exists()) {
                log.error("Failed to download HDX CSV: resource is empty.");
                return;
            }

            ImportResult result = hdxImportService.importFromResource(csvResource);
            log.info("HDX automated import completed. Imported: {}, Skipped: {}", result.getImported(), result.getSkipped());

        } catch (Exception e) {
            log.error("Failed to download or import HDX data: {}", e.getMessage(), e);
        }
    }
}