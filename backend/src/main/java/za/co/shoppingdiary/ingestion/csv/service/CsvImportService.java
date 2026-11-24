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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import za.co.shoppingdiary.ingestion.csv.dto.ImportResult;
import za.co.shoppingdiary.ingestion.csv.dto.ImportResult.RowError;
import za.co.shoppingdiary.product.entity.Product;
import za.co.shoppingdiary.price.OfferPricing;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CsvImportService {

    private static final GeometryFactory GEOMETRY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private final RetailerRepository retailers;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;

    private final PlatformTransactionManager transactionManager;

    public ImportResult importFile(MultipartFile file) {
        int imported = 0;
        int skipped = 0;
        List<ImportResult.RowError> errors = new ArrayList<>();

        var transaction = new TransactionTemplate(transactionManager);

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
                    boolean created = Boolean.TRUE.equals(transaction.execute(status -> processRow(row)));
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

    private boolean processRow(CSVRecord row) {

        // 1. Retailer
        String retailerSlug = get(row, "retailer_slug");
        if (retailerSlug == null) throw new IllegalArgumentException("retailer_slug is required");
        Retailer retailer = retailers.findBySlug(retailerSlug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown retailer: " + retailerSlug));

        // 2. Store
        String storeCode = get(row, "store_code");
        if (storeCode == null) throw new IllegalArgumentException("store_code is required");
        if (storeCode.equalsIgnoreCase("ONLINE") || !retailer.isActive()) {
            throw new IllegalArgumentException("Select an active physical retailer branch");
        }
        Store store = stores.findByRetailerIdAndStoreCode(retailer.getId(), storeCode)
                .orElseGet(() -> createStore(row, retailer));
        if (!store.isActive()) throw new IllegalArgumentException("Branch is inactive");

        String barcode = get(row, "barcode");
        String productName = get(row, "product_name");
        if (productName == null) throw new IllegalArgumentException("product_name is required");
        if (barcode != null && !barcode.matches("[0-9]{8,20}")) throw new IllegalArgumentException("Invalid barcode");
        if (barcode == null && (get(row, "brand") == null || get(row, "size_text") == null)) {
            throw new IllegalArgumentException("Provide a barcode or both brand and package size");
        }
        Product product = resolveProduct(row, barcode, productName);

        // 4. Price observation
        BigDecimal shelfPrice = parseDecimal(row, "shelf_price", false);
        BigDecimal promoPrice = parseDecimal(row, "promo_price", false);
        String stockStatus = get(row, "stock_status");
        if (stockStatus == null) stockStatus = "UNKNOWN";
        else stockStatus = stockStatus.toUpperCase(Locale.ROOT).replace(' ', '_');

        if ((shelfPrice == null && "IN_STOCK".equals(stockStatus))
                || (shelfPrice != null && shelfPrice.signum() <= 0)
                || (promoPrice != null && (promoPrice.signum() <= 0 || shelfPrice == null
                    || promoPrice.compareTo(shelfPrice) > 0))) {
            throw new IllegalArgumentException("Price must be positive and an unconditional promotion may not exceed shelf price");
        }
        if (!List.of("IN_STOCK", "OUT_OF_STOCK", "UNKNOWN").contains(stockStatus)) {
            throw new IllegalArgumentException("Unknown stock status");
        }
        Instant observedAt = parseObservedAt(row);
        String source = getOrDefault(row, "source_type", "CSV").toUpperCase(Locale.ROOT);
        String evidence = getOrDefault(row, "source_reference", "CSV row " + row.getRecordNumber());
        if (!List.of("CSV", "MANUAL", "RECEIPT", "BROCHURE").contains(source)
                || evidence.length() > 500
                || (List.of("RECEIPT", "BROCHURE").contains(source) && get(row, "source_reference") == null)) {
            throw new IllegalArgumentException("Invalid price source or missing evidence reference");
        }
        String hash = sha256(source + "|" + product.getId() + "|" + store.getId() + "|" + observedAt
                + "|" + (shelfPrice == null ? "" : shelfPrice.stripTrailingZeros().toPlainString()) + "|"
                + (promoPrice == null ? "" : promoPrice.stripTrailingZeros().toPlainString()) + "|" + stockStatus);

        List<Integer> result = jdbc.queryForList(
                "INSERT INTO price_observations " +
                "(product_id, store_id, source_type, source_reference, observed_at, shelf_price, " +
                " promo_price, stock_status, verification_status, content_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UNVERIFIED', ?) " +
                "ON CONFLICT (content_hash) DO NOTHING RETURNING 1",
                Integer.class,
                product.getId(), store.getId(), source, evidence,
                java.sql.Timestamp.from(observedAt), shelfPrice, promoPrice, stockStatus, hash);

        return !result.isEmpty();
    }

    private Store createStore(CSVRecord row, Retailer retailer) {
        BigDecimal lat = parseDecimal(row, "store_lat", true);
        BigDecimal lon = parseDecimal(row, "store_lon", true);
        if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lon.abs().compareTo(BigDecimal.valueOf(180)) > 0
                || get(row, "store_name") == null || get(row, "store_address") == null) {
            throw new IllegalArgumentException("New branches require a name, address and valid coordinates");
        }
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
            if (existing.isPresent()) {
                var product = existing.get();
                String brand = get(row, "brand");
                String size = get(row, "size_text");
                if (brand != null && product.getBrand() != null && !brand.equalsIgnoreCase(product.getBrand())) {
                    throw new IllegalArgumentException("Barcode brand conflicts with the catalogue");
                }
                var incoming = OfferPricing.measure(null, null, size);
                var stored = OfferPricing.measure(product.getBaseQuantity(), product.getBaseUnit(), product.getSizeText());
                if (incoming != null && stored != null && (!incoming.unit().equals(stored.unit())
                        || incoming.quantity().compareTo(stored.quantity()) != 0)) {
                    throw new IllegalArgumentException("Barcode package size conflicts with the catalogue");
                }
                return product;
            }
        } else {
            var matches = products.findTop2ByBarcodeIsNullAndCanonicalNameIgnoreCaseAndBrandIgnoreCaseAndSizeTextIgnoreCase(
                    productName, get(row, "brand"), get(row, "size_text"));
            if (matches.size() > 1) throw new IllegalArgumentException("Multiple catalogue matches require a verified barcode");
            if (!matches.isEmpty()) return matches.getFirst();
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

    private static Instant parseObservedAt(CSVRecord row) {
        String value = get(row, "observed_at");
        if (value == null) throw new IllegalArgumentException("observed_at is required");
        try {
            Instant observed = value.length() == 10
                    ? LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                            .atStartOfDay(ZoneId.of("Africa/Johannesburg")).toInstant()
                    : OffsetDateTime.parse(value).toInstant();
            if (observed.isAfter(Instant.now())) throw new IllegalArgumentException("observed_at cannot be in the future");
            return observed;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("observed_at must be a date or ISO timestamp with offset");
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