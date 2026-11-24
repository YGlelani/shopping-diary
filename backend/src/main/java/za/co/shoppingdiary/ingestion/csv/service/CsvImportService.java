package za.co.shoppingdiary.ingestion.csv.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import za.co.shoppingdiary.ingestion.csv.dto.ImportResult;
import za.co.shoppingdiary.ingestion.csv.dto.ImportResult.RowError;
import za.co.shoppingdiary.product.entity.Product;
import za.co.shoppingdiary.product.repository.ProductRepository;
import za.co.shoppingdiary.retailer.entity.Retailer;
import za.co.shoppingdiary.retailer.repository.RetailerRepository;
import za.co.shoppingdiary.store.entity.Store;
import za.co.shoppingdiary.store.repository.StoreRepository;

import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CsvImportService {

    private static final GeometryFactory GEOMETRY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private final RetailerRepository retailers;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;

    @Transactional
    public ImportResult importFile(MultipartFile file) {
        int imported = 0;
        int skipped = 0;
        List<ImportResult.RowError> errors = new ArrayList<>();

        Map<String, Store> storeCache = new HashMap<>();
        Map<String, Product> productCache = new HashMap<>();

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
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
                    boolean created = processRow(row, storeCache, productCache);
                    if (created) imported++;
                    else skipped++;
                } catch (Exception ex) {
                    errors.add(new ImportResult.RowError(rowNumber, ex.getMessage()));
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to read CSV: " + ex.getMessage(), ex);
        }

        return new ImportResult(imported, skipped, errors);
    }

    private boolean processRow(CSVRecord row, Map<String, Store> storeCache, Map<String, Product> productCache) {

        // 1. Retailer
        String retailerSlug = get(row, "retailer_slug");
        if (retailerSlug == null) throw new IllegalArgumentException("retailer_slug is required");
        Retailer retailer = retailers.findBySlug(retailerSlug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown retailer: " + retailerSlug));

        // 2. Store
        String storeCode = get(row, "store_code");
        if (storeCode == null) throw new IllegalArgumentException("store_code is required");
        String storeKey = retailer.getId() + ":" + storeCode;
        Store store = storeCache.get(storeKey);
        if (store == null) {
            store = stores.findByRetailerIdAndStoreCode(retailer.getId(), storeCode)
                    .orElseGet(() -> createStore(row, retailer));
            storeCache.put(storeKey, store);
        }

        // 3. Product
        String barcode = get(row, "barcode");
        String productName = get(row, "product_name");
        if (productName == null) throw new IllegalArgumentException("product_name is required");

        String productKey = barcode != null ? "barcode:" + barcode : "name:" + productName.toLowerCase();
        Product product = productCache.get(productKey);
        if (product == null) {
            product = resolveProduct(row, barcode, productName);
            productCache.put(productKey, product);
        }

        // 4. Price observation
        BigDecimal shelfPrice = parseDecimal(row, "shelf_price", true);
        BigDecimal promoPrice = parseDecimal(row, "promo_price", false);
        String stockStatus = get(row, "stock_status");
        if (stockStatus == null) stockStatus = "UNKNOWN";
        else stockStatus = stockStatus.toUpperCase().replace(' ', '_');

        LocalDate observedDate = parseObservedDate(row);
        Instant observedAt = observedDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);

        String hash = sha256(product.getId() + "|" + store.getId() + "|" + shelfPrice + "|" + observedDate);

        List<Integer> result = jdbc.queryForList(
                "INSERT INTO price_observations " +
                "(product_id, store_id, source_type, source_reference, observed_at, shelf_price, " +
                " promo_price, stock_status, verification_status, content_hash) " +
                "VALUES (?, ?, 'CSV', ?, ?, ?, ?, ?, 'UNVERIFIED', ?) " +
                "ON CONFLICT (content_hash) DO NOTHING RETURNING 1",
                Integer.class,
                product.getId(), store.getId(), row.getRecordNumber(),
                java.sql.Timestamp.from(observedAt), shelfPrice, promoPrice, stockStatus, hash);

        return !result.isEmpty();
    }

    private Store createStore(CSVRecord row, Retailer retailer) {
        BigDecimal lat = parseDecimal(row, "store_lat", true);
        BigDecimal lon = parseDecimal(row, "store_lon", true);
        Point point = GEOMETRY.createPoint(new Coordinate(lon.doubleValue(), lat.doubleValue()));

        Store s = new Store();
        s.setRetailerId(retailer.getId());
        s.setStoreCode(get(row, "store_code"));
        s.setName(getOrDefault(row, "store_name", "Unnamed store"));
        s.setAddress(get(row, "store_address"));
        s.setCity(get(row, "store_city"));
        s.setProvince(get(row, "store_province"));
        s.setLocation(point);
        return stores.saveAndFlush(s);
    }

    private Product resolveProduct(CSVRecord row, String barcode, String productName) {
        if (barcode != null) {
            var existing = products.findByBarcode(barcode);
            if (existing.isPresent()) return existing.get();
        } else {
            var match = products.findFirstByBarcodeIsNullAndCanonicalNameIgnoreCaseAndBrandIgnoreCaseAndSizeTextIgnoreCase(
                    productName, get(row, "brand"), get(row, "size_text"));
            if (match.isPresent()) return match.get();
        }

        Product p = new Product();
        p.setCanonicalName(productName);
        p.setBrand(get(row, "brand"));
        p.setBarcode(barcode);
        p.setSizeText(get(row, "size_text"));
        return products.saveAndFlush(p);
    }

    // ---- helpers ----

    private static String get(CSVRecord row, String column) {
        if (!row.isMapped(column)) return null;
        String value = row.get(column);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String getOrDefault(CSVRecord row, String column, String fallback) {
        String v = get(row, column);
        return v != null ? v : fallback;
    }

    private static BigDecimal parseDecimal(CSVRecord row, String column, boolean required) {
        String value = get(row, column);
        if (value == null) {
            if (required) throw new IllegalArgumentException(column + " is required");
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(column + " must be a number (got: " + value + ")");
        }
    }

    private static LocalDate parseObservedDate(CSVRecord row) {
        String value = get(row, "observed_at");
        if (value == null) return LocalDate.now();
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("observed_at must be YYYY-MM-DD (got: " + value + ")");
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}