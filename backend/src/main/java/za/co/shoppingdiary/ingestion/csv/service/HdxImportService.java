package za.co.shoppingdiary.ingestion.csv.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import za.co.shoppingdiary.ingestion.csv.dto.ImportResult;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HdxImportService {

    private final JdbcTemplate jdbc;

    /**
     * Entry point for automated downloads (Resource)
     */
    @Transactional
    public ImportResult importFromResource(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            return parseAndImport(inputStream);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process HDX resource", e);
        }
    }

    /**
     * Entry point for manual Swagger/UI uploads (MultipartFile)
     */
    @Transactional
    public ImportResult importHdxCsv(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return parseAndImport(inputStream);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read uploaded HDX CSV: " + e.getMessage(), e);
        }
    }

    /**
     * The shared core logic for parsing the HDX CSV
     */
    private ImportResult parseAndImport(InputStream inputStream) {
        int imported = 0;
        int skipped = 0;
        List<ImportResult.RowError> errors = new ArrayList<>();

        // Ensure the WFP retailer exists
        Long retailerId = getOrCreateWfpRetailer();

        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreSurroundingSpaces(true)
                        .setTrim(true)
                        .build()
                        .parse(reader)) {

            for (CSVRecord row : parser) {
                long rowNumber = row.getRecordNumber();
                try {
                    String dateStr = get(row, "date");
                    String market = get(row, "admin1"); // WFP CSV often uses admin1 or market
                    if (market == null)
                        market = get(row, "market"); // fallback

                    String commodity = get(row, "commodity");
                    String priceStr = get(row, "price");
                    String currency = get(row, "currency");

                    if (dateStr == null || market == null || commodity == null || priceStr == null) {
                        skipped++;
                        continue;
                    }

                    // Only import ZAR prices
                    if (currency != null && !currency.equalsIgnoreCase("ZAR")) {
                        skipped++;
                        continue;
                    }

                    LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                    BigDecimal price = new BigDecimal(priceStr);

                    Long storeId = getOrCreateHistoricalStore(retailerId, market);
                    Long productId = getOrCreateHistoricalProduct(commodity);

                    String hash = sha256(productId + "|" + storeId + "|" + price + "|" + date);

                    int rows = jdbc.update(
                            """
                                    INSERT INTO price_observations
                                    (product_id, store_id, source_type, source_reference, observed_at, shelf_price, stock_status, verification_status, content_hash)
                                    VALUES (?, ?, 'CSV', 'WFP-Historical', ?, ?, 'IN_STOCK', 'VERIFIED', ?)
                                    ON CONFLICT (content_hash) DO NOTHING
                                    """,
                            productId, storeId, java.sql.Timestamp.valueOf(date.atStartOfDay()), price, hash);

                    if (rows > 0)
                        imported++;
                    else
                        skipped++;

                } catch (Exception ex) {
                    errors.add(new ImportResult.RowError(rowNumber, ex.getMessage()));
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to read HDX CSV data: " + ex.getMessage(), ex);
        }

        return new ImportResult(imported, skipped, errors);
    }

    private String get(CSVRecord row, String column) {
        if (!row.isMapped(column))
            return null;
        String val = row.get(column);
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private Long getOrCreateWfpRetailer() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM retailers WHERE slug = 'wfp'", Long.class);
        if (!ids.isEmpty())
            return ids.get(0);
        jdbc.update("INSERT INTO retailers (name, slug) VALUES ('WFP Historical', 'wfp')");
        return jdbc.queryForObject("SELECT id FROM retailers WHERE slug = 'wfp'", Long.class);
    }

    private Long getOrCreateHistoricalStore(Long retailerId, String marketName) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM stores WHERE retailer_id = ? AND name = ?",
                Long.class, retailerId, marketName);
        if (!ids.isEmpty())
            return ids.get(0);

        jdbc.update("""
                INSERT INTO stores (retailer_id, name, store_code, location, city, store_type)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(24.0, -29.0), 4326), ?, 'SUPERMARKET')
                """, retailerId, marketName, "WFP-" + marketName.replace(" ", "-"), marketName);

        return jdbc.queryForObject("SELECT id FROM stores WHERE retailer_id = ? AND name = ?", Long.class, retailerId,
                marketName);
    }

    private Long getOrCreateHistoricalProduct(String commodity) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM products WHERE canonical_name = ?", Long.class, commodity);
        if (!ids.isEmpty())
            return ids.get(0);

        jdbc.update("INSERT INTO products (canonical_name, base_unit, category) VALUES (?, 'kg', 'Staples')",
                commodity);
        return jdbc.queryForObject("SELECT id FROM products WHERE canonical_name = ?", Long.class, commodity);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}