
package za.co.shoppingdiary.ingestion.csv.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import za.co.shoppingdiary.ingestion.csv.dto.ImportResult;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HdxImportService {

    private final JdbcTemplate jdbc;

    @Transactional
    public ImportResult importFromResource(
            Resource resource
    ) {
        if (resource == null) {
            throw new IllegalArgumentException(
                    "HDX resource is required"
            );
        }

        try (InputStream stream =
                     resource.getInputStream()) {

            return parseAndImport(stream);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to import HDX resource",
                    ex
            );
        }
    }

    @Transactional
    public ImportResult importHdxCsv(
            MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "CSV file cannot be empty"
            );
        }

        try (InputStream stream =
                     file.getInputStream()) {

            return parseAndImport(stream);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to import HDX CSV",
                    ex
            );
        }
    }

    private ImportResult parseAndImport(
            InputStream stream
    ) {
        int imported = 0;
        int skipped = 0;

        List<ImportResult.RowError> errors =
                new ArrayList<>();

        Long retailerId = getOrCreateWfpRetailer();

        try (
            Reader reader = new InputStreamReader(
                    stream,
                    StandardCharsets.UTF_8
            );

            CSVParser parser =
                    CSVFormat.DEFAULT.builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setIgnoreSurroundingSpaces(true)
                            .setTrim(true)
                            .build()
                            .parse(reader)
        ) {
            for (CSVRecord row : parser) {

                try {
                    String dateText = get(row, "date");

                    String market = get(row, "market");

                    if (market == null) {
                        market = get(row, "admin1");
                    }

                    String commodity =
                            get(row, "commodity");

                    String priceText =
                            get(row, "price");

                    String currency =
                            get(row, "currency");

                    if (dateText == null
                            || market == null
                            || commodity == null
                            || priceText == null) {

                        skipped++;
                        continue;
                    }

                    if (currency != null
                            && !currency.equalsIgnoreCase(
                                    "ZAR"
                            )) {

                        skipped++;
                        continue;
                    }

                    LocalDate date =
                            LocalDate.parse(dateText);

                    if (date.isAfter(LocalDate.now())) {
                        skipped++;
                        continue;
                    }

                    BigDecimal price =
                            new BigDecimal(priceText);

                    if (price.signum() <= 0) {
                        skipped++;
                        continue;
                    }

                    Long storeId =
                            getOrCreateHistoricalStore(
                                    retailerId,
                                    market
                            );

                    Long productId =
                            getOrCreateHistoricalProduct(
                                    commodity
                            );

                    String hash = sha256(
                            productId + "|" +
                            storeId + "|" +
                            price + "|" +
                            date
                    );

                    Timestamp observedAt =
                            Timestamp.from(
                                    date.atStartOfDay()
                                            .toInstant(
                                                    ZoneOffset.UTC
                                            )
                            );

                    int rows = jdbc.update(
                            """
                            INSERT INTO price_observations (
                                product_id,
                                store_id,
                                source_type,
                                source_reference,
                                observed_at,
                                shelf_price,
                                stock_status,
                                verification_status,
                                content_hash
                            )
                            VALUES (
                                ?, ?,
                                'CSV',
                                'WFP-Historical',
                                ?,
                                ?,
                                'UNKNOWN',
                                'UNVERIFIED',
                                ?
                            )
                            ON CONFLICT (content_hash)
                            DO NOTHING
                            """,
                            productId,
                            storeId,
                            observedAt,
                            price,
                            hash
                    );

                    if (rows > 0) {
                        imported++;
                    } else {
                        skipped++;
                    }

                } catch (Exception ex) {
                    errors.add(
                            new ImportResult.RowError(
                                    row.getRecordNumber(),
                                    ex.getMessage()
                            )
                    );
                }
            }

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to parse HDX CSV",
                    ex
            );
        }

        return new ImportResult(
                imported,
                skipped,
                errors
        );
    }

    private String get(
            CSVRecord row,
            String column
    ) {
        if (!row.isMapped(column)) {
            return null;
        }

        String value = row.get(column);

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private Long getOrCreateWfpRetailer() {
        return jdbc.queryForObject(
                """
                INSERT INTO retailers (
                    name,
                    slug,
                    active
                )
                VALUES (
                    'WFP Historical',
                    'wfp',
                    FALSE
                )
                ON CONFLICT (slug)
                DO UPDATE SET active = FALSE
                RETURNING id
                """,
                Long.class
        );
    }

    private Long getOrCreateHistoricalStore(
            Long retailerId,
            String marketName
    ) {
        List<Long> existing =
                jdbc.queryForList(
                        """
                        SELECT id
                        FROM stores
                        WHERE retailer_id = ?
                          AND name = ?
                        ORDER BY id
                        LIMIT 1
                        """,
                        Long.class,
                        retailerId,
                        marketName
                );

        if (!existing.isEmpty()) {
            jdbc.update(
                    """
                    UPDATE stores
                    SET active = FALSE
                    WHERE id = ?
                    """,
                    existing.get(0)
            );

            return existing.get(0);
        }

        String storeCode =
                "WFP-" + sha256(
                        marketName
                ).substring(0, 20);

        // Schema requires a point, but historical
        // data does not establish a real shop address.
        // Keep this record inactive.
        return jdbc.queryForObject(
                """
                INSERT INTO stores (
                    retailer_id,
                    name,
                    store_code,
                    location,
                    city,
                    store_type,
                    active
                )
                VALUES (
                    ?, ?, ?,
                    ST_SetSRID(
                        ST_MakePoint(0, 0),
                        4326
                    ),
                    ?,
                    'INDEPENDENT',
                    FALSE
                )
                ON CONFLICT (
                    retailer_id,
                    store_code
                )
                DO UPDATE SET active = FALSE
                RETURNING id
                """,
                Long.class,
                retailerId,
                marketName,
                storeCode,
                marketName
        );
    }

    private Long getOrCreateHistoricalProduct(
            String commodity
    ) {
        List<Long> existing =
                jdbc.queryForList(
                        """
                        SELECT id
                        FROM products
                        WHERE canonical_name = ?
                          AND barcode IS NULL AND brand IS NULL
                          AND category = 'Staples'
                          AND base_unit = 'kg' AND base_quantity IS NULL AND size_text IS NULL
                        ORDER BY id
                        LIMIT 1
                        """,
                        Long.class,
                        commodity
                );

        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        return jdbc.queryForObject(
                """
                INSERT INTO products (
                    canonical_name,
                    base_unit,
                    category
                )
                VALUES (?, 'kg', 'Staples')
                RETURNING id
                """,
                Long.class,
                commodity
        );
    }

    private String sha256(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            return HexFormat.of().formatHex(
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );

        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    ex
            );
        }
    }
}
