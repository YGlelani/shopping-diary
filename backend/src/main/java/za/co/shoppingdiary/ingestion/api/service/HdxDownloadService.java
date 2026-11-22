
package za.co.shoppingdiary.ingestion.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import za.co.shoppingdiary.ingestion.csv.dto.ImportResult;
import za.co.shoppingdiary.ingestion.csv.service.HdxImportService;

@Slf4j
@Service
@RequiredArgsConstructor
public class HdxDownloadService {

    private static final String HDX_CSV_URL =
            "https://data.humdata.org/dataset/"
            + "279cf561-64db-4bef-a9dd-077af3c5651a/"
            + "resource/"
            + "63910cdd-51a0-4244-94b6-0115d615aad2/"
            + "download/hdx_hapi_food_price_zaf.csv";

    private static final int MAX_DOWNLOAD_BYTES =
            25 * 1024 * 1024;

    private final HdxImportService hdxImportService;

    private final RestTemplate restTemplate =
            new RestTemplate();

    @Scheduled(
            cron = "0 0 3 * * MON",
            zone = "Africa/Johannesburg"
    )
    public void downloadAndImportHdxData() {

        log.info("Starting HDX historical price import");

        try {
            byte[] content = restTemplate.getForObject(
                    HDX_CSV_URL,
                    byte[].class
            );

            if (content == null || content.length == 0) {
                throw new IllegalStateException(
                        "HDX returned an empty file"
                );
            }

            if (content.length > MAX_DOWNLOAD_BYTES) {
                throw new IllegalStateException(
                        "HDX CSV exceeds the configured size limit"
                );
            }

            ByteArrayResource resource =
                    new ByteArrayResource(content);

            ImportResult result =
                    hdxImportService
                            .importFromResource(resource);

            log.info(
                    "HDX import complete: imported={}, skipped={}",
                    result.getImported(),
                    result.getSkipped()
            );

        } catch (RestClientException ex) {
            log.error(
                    "Failed to download HDX CSV",
                    ex
            );
            throw new IllegalStateException(
                    "HDX download failed",
                    ex
            );

        } catch (Exception ex) {
            log.error(
                    "HDX import failed",
                    ex
            );
            throw new IllegalStateException(
                    "HDX import failed",
                    ex
            );
        }
    }
}
