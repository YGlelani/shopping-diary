
package za.co.shoppingdiary.ingestion.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import za.co.shoppingdiary.product.entity.Product;
import za.co.shoppingdiary.product.repository.ProductRepository;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OpenFoodFactsService {

    private static final String HOST =
            "https://world.openfoodfacts.org";

    private static final Duration CACHE_TTL =
            Duration.ofMinutes(20);

    private static final long SEARCH_INTERVAL_MS =
            7_000L;

    private final ProductRepository products;

    private final ObjectMapper mapper =
            new ObjectMapper();

    private final HttpClient http;

    @Autowired
    public OpenFoodFactsService(ProductRepository products) {
        this(products, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }

    OpenFoodFactsService(ProductRepository products, HttpClient http) {
        this.products = products;
        this.http = http;
    }

    private final Map<String, CachedSearch> searches =
            new ConcurrentHashMap<>();

    private final Map<String, CachedCandidate> candidates =
            new ConcurrentHashMap<>();

    private long lastSearchRequestAt;
    private long lastProductRequestAt;
    private long retryAfter;
    private static final int MAX_CACHE_ENTRIES = 2000;

    @Value("${OFF_CONTACT_EMAIL:}")
    private String contactEmail;

    @Value("${SHOPPING_DIARY_CATALOGUE_IMPORT_ENABLED:false}")
    private boolean writeEnabled;

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public record Candidate(
            String barcode,
            String name,
            String brand,
            String sizeText,
            String imageUrl,
            String sourceUrl,
            String department
    ) {}

    public record Item(
            String barcode,
            String name,
            String status,
            Long productId
    ) {}

    public record BulkRequest(
            List<String> barcodes,
            boolean dryRun,
            boolean confirm
    ) {}

    public record BulkResult(
            boolean dryRun,
            int requested,
            int ready,
            int imported,
            int imagesUpdated,
            List<Item> items
    ) {}

    public record ImportResult(
            boolean dryRun,
            String status,
            Long productId,
            Candidate product
    ) {}

    private record CachedSearch(
            Instant until,
            List<Candidate> values
    ) {}

    private record CachedCandidate(
            Instant until,
            Candidate value
    ) {}

    // ============================================
    // SEARCH
    // ============================================

    public List<Candidate> search(
            String query,
            int limit
    ) {
        if (query == null
                || query.trim().length() < 2
                || query.trim().length() > 60) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Search must contain 2–60 characters"
            );
        }

        if (limit < 1 || limit > 20) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Limit must be between 1 and 20"
            );
        }

        String key = query.trim()
                .toLowerCase(Locale.ROOT);

        CachedSearch cached = searches.get(key);

        if (cached != null
                && cached.until().isAfter(Instant.now())) {

            return cached.values()
                    .stream()
                    .limit(limit)
                    .toList();
        }

        throttle(true);

        String url = HOST
                + "/cgi/search.pl?search_terms="
                + URLEncoder.encode(
                    query.trim(),
                    StandardCharsets.UTF_8
                )
                + "&search_simple=1"
                + "&action=process"
                + "&json=1"
                + "&tagtype_0=countries"
                + "&tag_contains_0=contains"
                + "&tag_0=south-africa"
                + "&page_size=60"
                + "&fields=code%2Cproduct_name"
                + "%2Cproduct_name_en%2Cbrands"
                + "%2Cquantity%2Ccountries_tags%2Ccategories_tags"
                + "%2Cimage_front_url%2Cimage_url";

        JsonNode root = fetch(url);

        if (!root.path("products").isArray()) {
            throw error(
                    HttpStatus.BAD_GATEWAY,
                    "Unexpected Open Food Facts search response"
            );
        }

        List<Candidate> result = new ArrayList<>();

        LinkedHashSet<String> seen =
                new LinkedHashSet<>();

        for (JsonNode node : root.path("products")) {

            Candidate candidate = parse(node);

            if (candidate != null
                    && seen.add(candidate.barcode())) {

                result.add(candidate);

                remember(candidates,
                        candidate.barcode(),
                        new CachedCandidate(
                                Instant.now().plus(CACHE_TTL),
                                candidate
                        )
                );

                if (result.size() == 20) {
                    break;
                }
            }
        }

        remember(searches,
                key,
                new CachedSearch(
                        Instant.now().plus(CACHE_TTL),
                        List.copyOf(result)
                )
        );

        return result.stream()
                .limit(limit)
                .toList();
    }

    // ============================================
    // BARCODE PREVIEW
    // ============================================

    public Candidate previewBarcode(String barcode) {

        validateBarcode(barcode);

        CachedCandidate cached =
                candidates.get(barcode);

        if (cached != null
                && cached.until().isAfter(Instant.now())) {

            return cached.value();
        }

        throttle(false);
        String url = HOST
                + "/api/v2/product/"
                + barcode
                + ".json?fields=code%2Cproduct_name"
                + "%2Cproduct_name_en%2Cbrands"
                + "%2Cquantity%2Ccountries_tags%2Ccategories_tags"
                + "%2Cimage_front_url%2Cimage_url";

        JsonNode root = fetch(url);

        Candidate candidate =
                parse(root.path("product"));

        if (candidate == null
                || !barcode.equals(candidate.barcode())) {

            throw error(
                    HttpStatus.NOT_FOUND,
                    "No eligible South African product found"
            );
        }

        remember(candidates,
                barcode,
                new CachedCandidate(
                        Instant.now().plus(CACHE_TTL),
                        candidate
                )
        );

        return candidate;
    }

    // ============================================
    // BULK PREVIEW AND IMPORT
    // ============================================

    @Transactional
    public BulkResult bulkImport(BulkRequest request) {

        if (request == null
                || request.barcodes() == null
                || request.barcodes().isEmpty()
                || request.barcodes().size() > 20) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Select between 1 and 20 products"
            );
        }

        LinkedHashSet<String> selected =
                new LinkedHashSet<>(request.barcodes());

        if (selected.size() != request.barcodes().size()) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Remove duplicate barcodes from selection"
            );
        }

        List<Candidate> chosen =
                new ArrayList<>();

        for (String barcode : selected) {
            validateBarcode(barcode);
            CachedCandidate cached = candidates.get(barcode);
            if (cached == null || !cached.until().isAfter(Instant.now())) {
                throw error(HttpStatus.CONFLICT, "Preview expired for barcode " + barcode + ". Search again.");
            }
            chosen.add(cached.value());
        }

        List<Item> checked =
                new ArrayList<>();

        int ready = 0;

        for (Candidate candidate : chosen) {
            Item item = inspect(candidate);
            checked.add(item);

            if (item.status().startsWith("READY_")) {
                ready++;
            }
        }

        // ----------------------------------------
        // DRY RUN: ALWAYS READ-ONLY
        // ----------------------------------------

        if (request.dryRun()) {

            return new BulkResult(
                    true,
                    chosen.size(),
                    ready,
                    0,
                    0,
                    checked
            );
        }

        // ----------------------------------------
        // DATABASE WRITE SAFETY
        // ----------------------------------------

        if (!writeEnabled) {

            throw error(
                    HttpStatus.FORBIDDEN,
                    "Catalogue writes are disabled on this backend."
            );
        }

        if (!request.confirm()) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Explicit confirmation required"
            );
        }

        // All selected products must be eligible.
        // No partial batch imports.
        if (ready != chosen.size()) {

            throw error(
                    HttpStatus.CONFLICT,
                    "Some products already exist or require review. "
                            + "Review your "
                            + "selection before importing."
            );
        }

        List<Item> saved =
                new ArrayList<>();

        int inserted = 0;
        int imagesUpdated = 0;

        for (int i = 0; i < chosen.size(); i++) {

            Candidate candidate = chosen.get(i);
            Item item = checked.get(i);

            if ("READY_IMAGE_UPDATE".equals(
                    item.status()
            )) {

                if (products.addImageIfMissing(item.productId(), candidate.imageUrl(), candidate.sourceUrl()) != 1) {
                    throw error(HttpStatus.CONFLICT, "Product image changed; retry preview");
                }

                imagesUpdated++;

                saved.add(new Item(
                        candidate.barcode(),
                        candidate.name(),
                        "IMAGE_UPDATED",
                        item.productId()
                ));

            } else {

                Product product = new Product();

                product.setBarcode(
                        candidate.barcode()
                );

                product.setCanonicalName(
                        candidate.name()
                );

                product.setBrand(
                        candidate.brand()
                );

                product.setSizeText(
                        candidate.sizeText()
                );

                product.setImageUrl(candidate.imageUrl());
                if (candidate.department() != null) {
                    products.findDepartmentId(candidate.department()).ifPresent(product::setCategoryId);
                }
                product.setCatalogueSourceUrl(candidate.sourceUrl());
                if (candidate.imageUrl() != null) {
                    product.setImageSourceUrl(candidate.sourceUrl());
                }
                try {
                    products.saveAndFlush(product);
                } catch (DataIntegrityViolationException ex) {
                    throw error(HttpStatus.CONFLICT, "Catalogue changed; retry preview");
                }

                inserted++;

                saved.add(new Item(
                        candidate.barcode(),
                        candidate.name(),
                        "IMPORTED",
                        product.getId()
                ));
            }
        }

        return new BulkResult(
                false,
                saved.size(),
                ready,
                inserted,
                imagesUpdated,
                saved
        );
    }

    // ============================================
    // EXISTING PRODUCT CHECKS
    // ============================================

    private Item inspect(Candidate candidate) {

        var byBarcode =
                products.findByBarcode(
                        candidate.barcode()
                );

        if (byBarcode.isPresent()) {

            Product existing = byBarcode.get();

            if ((existing.getImageUrl() == null
                    || existing.getImageUrl().isBlank())
                    && candidate.imageUrl() != null) {

                return new Item(
                        candidate.barcode(),
                        candidate.name(),
                        "READY_IMAGE_UPDATE",
                        existing.getId()
                );
            }

            return new Item(
                    candidate.barcode(),
                    candidate.name(),
                    "ALREADY_EXISTS",
                    existing.getId()
            );
        }

        var unbarcoded = products.findFirstByBarcodeIsNullAndCanonicalNameIgnoreCaseAndBrandIgnoreCaseAndSizeTextIgnoreCase(
                candidate.name(), candidate.brand(), candidate.sizeText());
        if (unbarcoded.isPresent()) {
            return new Item(candidate.barcode(), candidate.name(), "NAME_REVIEW_REQUIRED", unbarcoded.get().getId());
        }

        return new Item(
                candidate.barcode(),
                candidate.name(),
                "READY_TO_IMPORT",
                null
        );
    }

    // ============================================
    // NORMALIZATION
    // ============================================

    public Candidate exportCandidate(JsonNode node) { return parse(node); }

    private Candidate parse(JsonNode node) {

        if (node == null || !node.isObject()) {
            return null;
        }

        String barcode = text(node, "code");

        if (barcode == null
                || !barcode.matches("[0-9]{8,14}")) {

            return null;
        }

        boolean southAfrica = false;

        for (JsonNode country :
                node.path("countries_tags")) {

            if ("en:south-africa".equalsIgnoreCase(
                    country.asText()
            )) {

                southAfrica = true;
                break;
            }
        }

        if (!southAfrica) {
            return null;
        }

        String name = text(
                node,
                "product_name"
        );

        if (name == null) {
            name = text(
                    node,
                    "product_name_en"
            );
        }

        if (name == null || name.length() < 3) {
            return null;
        }

        String brand = shorten(
                text(node, "brands"),
                100
        );

        String size = shorten(
                text(node, "quantity"),
                50
        );

        if (name.length() > 255) {
            return null;
        }

        String image = text(
                node,
                "image_front_url"
        );

        if (image == null) {
            image = text(
                    node,
                    "image_url"
            );
        }

        if (image != null) {

            try {
                URI uri = URI.create(image);

                if (!"https".equalsIgnoreCase(
                        uri.getScheme()
                )
                        || !"images.openfoodfacts.org"
                            .equalsIgnoreCase(uri.getHost())
                        || image.length() > 1000) {

                    image = null;
                }

            } catch (IllegalArgumentException ex) {
                image = null;
            }
        }

        return new Candidate(
                barcode,
                name,
                brand,
                size,
                image,
                HOST + "/product/" + barcode,
                department(node.path("categories_tags"))
        );
    }

    private String department(JsonNode tags) {
        var categories = new java.util.HashSet<String>();
        tags.forEach(tag -> categories.add(tag.asText()));
        if (categories.contains("en:frozen-foods")) return "Frozen foods";
        if (categories.contains("en:sweet-snacks") || categories.contains("en:salty-snacks")) return "Snacks and biscuits";
        if (categories.contains("en:beverages")) return "Beverages";
        if (categories.contains("en:milks") || categories.contains("en:dairies") || categories.contains("en:eggs")) {
            return "Dairy and eggs";
        }
        if (categories.contains("en:breads") || categories.contains("en:viennoiseries")) return "Bakery";
        if (categories.contains("en:meats") || categories.contains("en:fishes") || categories.contains("en:poultries")) {
            return "Meat, poultry and fish";
        }
        if (categories.contains("en:fresh-fruits") || categories.contains("en:fresh-vegetables")) {
            return "Fruit and vegetables";
        }
        if (categories.contains("en:rices") || categories.contains("en:pastas") || categories.contains("en:legumes")) {
            return "Pantry essentials";
        }
        return null;
    }

    private String text(
            JsonNode node,
            String field
    ) {

        JsonNode value = node.path(field);

        if (!value.isTextual()) {
            return null;
        }

        String cleaned = value.asText()
                .trim()
                .replaceAll("\\p{Cntrl}", " ");

        return cleaned.isBlank()
                ? null
                : cleaned;
    }

    private String shorten(
            String value,
            int max
    ) {

        return value == null
                ? null
                : value.substring(
                    0,
                    Math.min(value.length(), max)
                );
    }

    // ============================================
    // HTTP AND RATE LIMITING
    // ============================================

    private void validateBarcode(String barcode) {

        if (barcode == null
                || !barcode.matches("[0-9]{8,14}")) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Barcode must contain 8–14 digits"
            );
        }
    }

    private synchronized <T> void remember(Map<String, T> cache, String key, T entry) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, entry);
    }

    private synchronized void throttle(boolean search) {
        long now = System.currentTimeMillis();
        if (now < retryAfter) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "Open Food Facts is temporarily busy. Retry in a minute.");
        }
        long interval = search ? SEARCH_INTERVAL_MS : 4_100L;
        long previous = search ? lastSearchRequestAt : lastProductRequestAt;
        if (now - previous < interval) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, "Please wait a few seconds before another Open Food Facts request.");
        }
        if (search) lastSearchRequestAt = now;
        else lastProductRequestAt = now;
    }

    private synchronized void pauseProvider() {
        retryAfter = System.currentTimeMillis() + 60_000L;
    }

    private JsonNode fetch(String url) {

        if (contactEmail == null
                || !contactEmail.matches(
                    "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"
                )) {

            throw error(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OFF_CONTACT_EMAIL must be configured"
            );
        }

        HttpRequest request = HttpRequest.newBuilder(
                    URI.create(url)
                )
                .timeout(Duration.ofSeconds(20))
                .header(
                    "User-Agent",
                    "ShoppingDiary/0.2 ("
                            + contactEmail + ")"
                )
                .header(
                    "Accept",
                    "application/json"
                )
                .GET()
                .build();

        try {

            HttpResponse<String> response =
                    http.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8
                            )
                    );

            if (response.statusCode() == 429
                    || response.statusCode() == 503) {
                pauseProvider();

                throw error(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Open Food Facts is temporarily busy. "
                                + "Wait a minute and retry."
                );
            }

            if (response.statusCode() == 404) {
                throw error(HttpStatus.NOT_FOUND, "No matching Open Food Facts product found");
            }
            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {

                throw error(
                        HttpStatus.BAD_GATEWAY,
                        "Open Food Facts returned HTTP "
                                + response.statusCode()
                );
            }

            JsonNode body = mapper.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw error(HttpStatus.BAD_GATEWAY, "Unexpected Open Food Facts response");
            }
            return body;

        } catch (InterruptedException ex) {

            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Product request interrupted",
                    ex
            );

        } catch (IOException ex) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Cannot reach Open Food Facts",
                    ex
            );
        }
    }

    private ResponseStatusException error(
            HttpStatus status,
            String message
    ) {

        return new ResponseStatusException(
                status,
                message
        );
    }
}
