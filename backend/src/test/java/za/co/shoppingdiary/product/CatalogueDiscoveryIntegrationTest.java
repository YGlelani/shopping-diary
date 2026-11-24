package za.co.shoppingdiary.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import za.co.shoppingdiary.ingestion.csv.service.CsvImportService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import za.co.shoppingdiary.auth.service.JwtService;
import za.co.shoppingdiary.ingestion.scraper.SampleRetailerScraper;
import za.co.shoppingdiary.user.entity.Role;
import za.co.shoppingdiary.user.entity.User;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DISCOVERY_POSTGIS_TEST", matches = "true")
@SpringBootTest(properties = {
    "spring.config.import=",
    "logging.level.root=WARN",
    "logging.level.za.co.shoppingdiary=INFO",
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55436/discovery_test",
    "spring.datasource.username=postgres",
    "spring.datasource.password=discovery_test",
    "spring.flyway.url=jdbc:postgresql://127.0.0.1:55436/discovery_test",
    "spring.flyway.user=postgres",
    "spring.flyway.password=discovery_test",
    "spring.data.redis.host=127.0.0.1",
    "spring.data.redis.port=56379",
    "app.jwt.secret=bG9jYWwtdGVzdC1zaWduaW5nLWtleS13aXRoLWF0LWxlYXN0LTMyLWJ5dGVz",
    "app.bootstrap.admin-email=",
    "app.bootstrap.admin-password=",
    "OFF_CONTACT_EMAIL=",
    "SHOPPING_DIARY_CATALOGUE_IMPORT_ENABLED=false",
    "loyaltyhub.api.key=",
    "reefapi.api.key="
})
@Transactional
class CatalogueDiscoveryIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired SampleRetailerScraper scraper;
    @Autowired CsvImportService csv;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private long breadId;
    private long storeId;

    @BeforeEach
    void seedCatalogue() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        long bakeryId = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'bakery'", Long.class);
        long breadCategory = jdbc.queryForObject("""
            INSERT INTO categories (name, slug, parent_id, depth)
            VALUES ('Bread', 'test-bread', ?, 1) RETURNING id
            """, Long.class, bakeryId);
        breadId = jdbc.queryForObject("""
            INSERT INTO products (canonical_name, brand, barcode, size_text, category_id)
            VALUES ('Whole Wheat Bread','Sasko','0012345678901','700 g', ?) RETURNING id
            """, Long.class, breadCategory);

        jdbc.update("""
            INSERT INTO products (canonical_name, brand, size_text, category, base_quantity, base_unit)
            VALUES ('Long Grain Rice','Tastic', '2000 g','Pantry essentials', NULL, NULL),
                   ('Long Grain Rice','Other Brand', NULL,'Pantry essentials', 1, 'kg'),
                   ('Full Cream Milk','Test Dairy', '2 L','Dairy and eggs', NULL, NULL),
                   ('Orange Juice 750ml','Test Juice', NULL,'Beverages', NULL, NULL),
                   ('Large Eggs','Test Farm', '6 pack','Dairy and eggs', NULL, NULL)
            """);
        storeId = jdbc.queryForObject("""
            INSERT INTO stores (retailer_id, name, location)
            SELECT id, 'Test Checkers', ST_SetSRID(ST_MakePoint(28, -26), 4326)
            FROM retailers WHERE slug = 'checkers' RETURNING id
            """, Long.class);
    }

    @Test
    void searchesLocalCatalogueByNameBrandSizeCategoryAndBarcodeWithoutPrices() throws Exception {
        search("bread").andExpect(jsonPath("$[0].productId").value(breadId))
                .andExpect(jsonPath("$[0].storeCount").value(0))
                .andExpect(jsonPath("$[0].minEffectivePrice").doesNotExist());
        search("SASKO").andExpect(jsonPath("$[0].barcode").value("0012345678901"));
        search("sasko bred 700g").andExpect(jsonPath("$[0].productId").value(breadId));
        search("rice 2kg").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].brand").value("Tastic"));
        search("rice 1000g").andExpect(jsonPath("$[0].sizeText").value("1 kg"));
        search("full cream milk 2000ml").andExpect(jsonPath("$[0].canonicalName").value("Full Cream Milk"));
        search("0012345678901").andExpect(jsonPath("$.length()").value(1));
        search("0012345678999").andExpect(jsonPath("$.length()").value(0));
        search("large eggs 6 pack").andExpect(jsonPath("$[0].canonicalName").value("Large Eggs"));
        search("750ml").andExpect(jsonPath("$[0].canonicalName").value("Orange Juice 750ml"));
        search("750ml").andExpect(jsonPath("$[0].sizeText").value("750ml"));
        mvc.perform(get("/api/v1/products/search").param("category", "Bakery"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].category").value("Bread"));
        mvc.perform(get("/api/v1/products/categories")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@ == 'Bakery')]").isNotEmpty());
        mvc.perform(get("/api/v1/products/search").param("lat", "NaN").param("lon", "28"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products/search").param("priced", "unknown"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products/search").param("q", "rice").param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].brand").value("Other Brand"));
    }

    @Test
    void sharesEligiblePriceRulesBetweenSearchAndComparison() throws Exception {
        observe("CURRENT_TIMESTAMP - interval '2 hours'", 25, 20, "IN_STOCK", "VERIFIED", "good");
        observe("CURRENT_TIMESTAMP + interval '2 hours'", 2, 1, "IN_STOCK", "VERIFIED", "future");

        jdbc.update("""
            INSERT INTO promotions (product_id, store_id, promo_type, promo_price, min_quantity, start_date, end_date, status)
            VALUES (?, ?, 'MULTIBUY', 35, 2, CURRENT_DATE - 1, CURRENT_DATE + 1, 'ACTIVE'),
                   (?, ?, 'DISCOUNT', 1, 1, CURRENT_DATE - 7, CURRENT_DATE - 2, 'ACTIVE')
            """, breadId, storeId, breadId, storeId);

        nearbySearch("true").andExpect(jsonPath("$[0].minEffectivePrice").value(20))
                .andExpect(jsonPath("$[0].storeCount").value(1))
                .andExpect(jsonPath("$[0].latestObservedAt").exists());
        prices().andExpect(jsonPath("$[0].promoPrice").value(20))
                .andExpect(jsonPath("$[0].storeName").value("Test Checkers"))
                .andExpect(jsonPath("$[0].verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$[0].promotions.length()").value(1))
                .andExpect(jsonPath("$[0].promotions[0].price").value(35))
                .andExpect(jsonPath("$[0].promotions[0].minQuantity").value(2));

        observe("CURRENT_TIMESTAMP - interval '1 hour'", 25, 20, "OUT_OF_STOCK", "VERIFIED", "out");
        nearbySearch("false").andExpect(jsonPath("$[0].productId").value(breadId))
                .andExpect(jsonPath("$[0].minEffectivePrice").doesNotExist());
        prices().andExpect(jsonPath("$.length()").value(0));

        observe("CURRENT_TIMESTAMP - interval '10 minutes'", null, null, "IN_STOCK", "VERIFIED", "missing");
        nearbySearch("false").andExpect(jsonPath("$[0].storeCount").value(0));
        prices().andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void addsAnUnpricedProductToAListAndRejectsNonAdminImports() throws Exception {
        String token = register("buyer@example.test");
        mvc.perform(post("/api/v1/admin/catalogue/open-food-facts/bulk")
                    .header("Authorization", token).contentType("application/json")
                    .content("{\"barcodes\":[\"0012345678901\"],\"confirm\":true}"))
                .andExpect(status().isForbidden());
        String result = mvc.perform(post("/api/v1/lists").header("Authorization", token)
                    .contentType("application/json").content("{\"name\":\"Groceries\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long listId = json.readTree(result).path("id").asLong();
        mvc.perform(post("/api/v1/lists/" + listId + "/items").header("Authorization", token)
                    .contentType("application/json").content("{\"productId\":" + breadId + ",\"quantity\":1}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.productId").value(breadId));
        mvc.perform(get("/api/v1/lists/" + listId).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].productId").value(breadId));
    }

    @Test
    void catalogueRemainsAvailableWhenProviderRequestsFail() throws Exception {
        var admin = new User();
        admin.setId(1L);
        admin.setEmail("catalogue-admin@example.test");
        admin.setDisplayName("Test Admin");
        admin.setRole(Role.ADMIN);
        String token = "Bearer " + jwt.issue(admin);
        mvc.perform(get("/api/v1/admin/catalogue/open-food-facts/capabilities").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.importEnabled").value(false));
        mvc.perform(get("/api/v1/admin/catalogue/open-food-facts/search").param("query", "bread")
                    .header("Authorization", token))
                .andExpect(status().isServiceUnavailable());

        search("bread").andExpect(jsonPath("$[0].productId").value(breadId));
    }

    @Test
    void retailerScrapingDoesNotAttachSameNameProductsToTheWrongBarcode() throws Exception {
        scraper.scrapeAndSave(Jsoup.parse("""
            <div class="product-card" data-price="28.99" data-barcode="0012345678911">
                <h3 class="product-name">Whole Wheat Bread</h3>
            </div>
            """), storeId);
        // The scraper uses the JVM clock, while the test transaction fixes CURRENT_TIMESTAMP.
        jdbc.update("""
            UPDATE price_observations SET observed_at = CURRENT_TIMESTAMP WHERE store_id = ?
            """, storeId);
        nearbySearch("false").andExpect(jsonPath("$[0].productId").value(breadId));
        mvc.perform(get("/api/v1/products/search").param("q", "0012345678911")
                .param("lat", "-26").param("lon", "28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].barcode").value("0012345678911"))
                .andExpect(jsonPath("$[0].minEffectivePrice").value(28.99));
    }

    @Test
    void comparesTheSameProductAcrossBranchesAndKeepsBasketTotalsConsistent() throws Exception {
        observe("CURRENT_TIMESTAMP - interval '2 hours'", 30, null, "IN_STOCK", "VERIFIED", "branch-one");
        long nearby = addBranch("Boxer nearby", "boxer", 28.02);
        long far = addBranch("Boxer outside radius", "boxer", 29.0);
        jdbc.update("""
            INSERT INTO price_observations (product_id, store_id, shelf_price, member_price,
                source_type, observed_at, stock_status, verification_status, content_hash)
            VALUES (?, ?, 29, 10, 'API', CURRENT_TIMESTAMP, 'IN_STOCK', 'UNVERIFIED', 'branch-two'),
                   (?, ?, 12, NULL, 'API', CURRENT_TIMESTAMP, 'IN_STOCK', 'VERIFIED', 'branch-far')
            """, breadId, nearby, breadId, far);
        jdbc.update("""
            INSERT INTO promotions (product_id, store_id, promo_type, promo_price, min_quantity, start_date, end_date, status)
            VALUES (?, ?, 'MULTIBUY', 50, 2, CURRENT_DATE - 1, CURRENT_DATE + 1, 'ACTIVE'),
                   (?, ?, 'DISCOUNT', 1, 1, CURRENT_DATE - 9, CURRENT_DATE - 2, 'ACTIVE')
            """, breadId, storeId, breadId, nearby);
        prices().andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].storeId").value(nearby))
                .andExpect(jsonPath("$[0].effectivePrice").value(29))
                .andExpect(jsonPath("$[0].memberPrice").value(10))
                .andExpect(jsonPath("$[0].unitPrice").value(41.43))
                .andExpect(jsonPath("$[0].unitLabel").value("kg"));

        mvc.perform(get("/api/v1/products/" + breadId + "/prices").param("lat", "-26").param("lon", "28")
                .param("quantity", "2").param("verification", "VERIFIED").param("minPrice", "48").param("maxPrice", "55"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].storeId").value(storeId))
                .andExpect(jsonPath("$[0].totalPrice").value(50))
                .andExpect(jsonPath("$[0].effectivePrice").value(25))
                .andExpect(jsonPath("$[0].cheapest").value(true));

        String token = register("comparison-buyer@example.test");
        String created = mvc.perform(post("/api/v1/lists").header("Authorization", token)
                .contentType("application/json").content("{\"name\":\"Comparison\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long listId = json.readTree(created).path("id").asLong();
        mvc.perform(post("/api/v1/lists/" + listId + "/items").header("Authorization", token)
                .contentType("application/json").content("{\"productId\":" + breadId + ",\"quantity\":2}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/lists/" + listId + "/baskets").header("Authorization", token)
                .param("lat", "-26").param("lon", "28"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].subtotal").value(50));
        mvc.perform(post("/api/v1/lists/" + listId + "/optimize").header("Authorization", token)
                .contentType("application/json").content("{\"lat\":-26,\"lon\":28,\"costPerKm\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.grandTotal").value(50));
    }

    @Test
    void separatesFreshnessFromVerificationAndDoesNotResurrectDisputedPrices() throws Exception {
        observe("CURRENT_TIMESTAMP - interval '5 hours'", 30, null, "IN_STOCK", "VERIFIED", "earlier");
        observe("CURRENT_TIMESTAMP - interval '3 hours'", 25, null, "IN_STOCK", "DISPUTED", "disputed");
        prices().andExpect(jsonPath("$.length()").value(0));
        jdbc.update("UPDATE price_observations SET source_type = 'COMMUNITY', observed_at = CURRENT_TIMESTAMP - interval '4 days' WHERE content_hash = 'earlier'");
        jdbc.update("DELETE FROM price_observations WHERE content_hash = 'disputed'");
        prices().andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reportsDirectoryCoverageAndPublishesOnlyReviewedBranchContributions() throws Exception {
        String buyer = register("contributor@example.test");
        mvc.perform(get("/api/v1/admin/directory").header("Authorization", buyer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/price-submissions").header("Authorization", buyer)).andExpect(status().isForbidden());

        var admin = new User();
        admin.setId(1L);
        admin.setEmail("directory-admin@example.test");
        admin.setDisplayName("Directory Admin");
        admin.setRole(Role.ADMIN);
        String token = "Bearer " + jwt.issue(admin);
        mvc.perform(get("/api/v1/admin/directory").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(20));
        long retailer = jdbc.queryForObject("SELECT id FROM retailers WHERE slug = 'boxer'", Long.class);
        mvc.perform(put("/api/v1/admin/directory/" + retailer + "/branches/TEST-BRANCH")
                .header("Authorization", token).contentType("application/json")
                .content("{\"name\":\"Boxer test branch\",\"address\":\"Test address\",\"city\":\"Polokwane\",\"province\":\"Limpopo\",\"lat\":-23.9,\"lon\":29.4,\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/directory/" + retailer + "/branches").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].usable").value(false));
        long branch = jdbc.queryForObject("SELECT id FROM stores WHERE retailer_id = ? AND store_code = 'TEST-BRANCH'",
                Long.class, retailer);
        mvc.perform(put("/api/v1/admin/directory/" + retailer + "/branches/TEST-BRANCH").param("branchId", String.valueOf(branch))
                .header("Authorization", token).contentType("application/json")
                .content("{\"name\":\"Corrected Boxer branch\",\"address\":\"Test address\",\"city\":\"Polokwane\",\"province\":\"Limpopo\",\"lat\":-23.91,\"lon\":29.4,\"active\":true}"))
                .andExpect(status().isOk()).andExpect(content().string(String.valueOf(branch)));
        mvc.perform(get("/api/v1/stores/areas").param("q", "Polok").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].city").value("Polokwane"));
        mvc.perform(put("/api/v1/admin/directory/" + retailer + "/aliases").header("Authorization", token)
                .contentType("application/json").content("{\"rawName\":\"Sasko exact 700g\",\"barcode\":\"0012345678901\"}"))
                .andExpect(status().isOk());

        String submitted = json.writeValueAsString(java.util.Map.of(
                "productId", breadId, "storeId", storeId, "price", 24, "inStock", true,
                "observedAt", java.time.OffsetDateTime.now().minusMinutes(1).toString(),
                "evidence", "Shelf reference"));
        mvc.perform(post("/api/v1/price-submissions").header("Authorization", buyer)
                .contentType("application/json").content(submitted)).andExpect(status().isOk());
        prices().andExpect(jsonPath("$.length()").value(0));
        String queue = mvc.perform(get("/api/v1/admin/price-submissions").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].barcode").value("0012345678901"))
                .andReturn().getResponse().getContentAsString();
        long submissionId = json.readTree(queue).get(0).path("id").asLong();
        mvc.perform(post("/api/v1/admin/price-submissions/" + submissionId).param("approve", "true")
                .header("Authorization", token)).andExpect(status().isOk());
        prices().andExpect(jsonPath("$[0].sourceType").value("COMMUNITY"))
                .andExpect(jsonPath("$[0].effectivePrice").value(24));
    }

    @Test
    void csvRetainsObservationSourceAndSeparatesIdenticalNamesWithDifferentPackageSizes() {
        jdbc.update("UPDATE stores SET store_code = 'CSV-TEST' WHERE id = ?", storeId);
        String date = java.time.OffsetDateTime.now().minusMinutes(3).toString();
        String rows = """
            retailer_slug,store_code,product_name,brand,size_text,shelf_price,stock_status,observed_at,source_type,source_reference
            checkers,CSV-TEST,White Rice,Test Farm,500 g,14,IN_STOCK,%s,RECEIPT,Test receipt A
            checkers,CSV-TEST,White Rice,Test Farm,2 kg,46,IN_STOCK,%s,RECEIPT,Test receipt B
            """.formatted(date, date);
        var file = new MockMultipartFile("file", "test.csv", "text/csv",
                rows.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertEquals(2, csv.importFile(file).getImported());
        org.junit.jupiter.api.Assertions.assertEquals(2, csv.importFile(file).getSkipped());
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject("""
            SELECT count(DISTINCT product_id) FROM price_observations
            WHERE store_id = ? AND source_type = 'RECEIPT'
            """, Integer.class, storeId));
    }

    @Test
    void findsSmallTownsAndProvincesWithoutRegisteredBranches() throws Exception {
        String token = register("locality-search@example.test");
        mvc.perform(get("/api/v1/stores/provinces").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(9));
        mvc.perform(get("/api/v1/stores/areas").param("q", "Bergville").param("province", "KwaZulu-Natal")
                .header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].city").value("Bergville"))
                .andExpect(jsonPath("$[0].province").value("KwaZulu-Natal"));
        mvc.perform(get("/api/v1/stores/areas").param("q", "0002").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].kind").value("POSTAL"));
    }

    @Test
    void nearbyUsesSpheroidDistanceAndDistinguishesStaleAndUnpricedBranches() throws Exception {
        String token = register("branch-coverage@example.test");
        long stale = addBranch("Historical branch", "boxer", 28.02);
        observe("CURRENT_TIMESTAMP - interval '1 hour'", 30, null, "IN_STOCK", "VERIFIED", "current-test");
        jdbc.update("""
            INSERT INTO price_observations (product_id, store_id, shelf_price, source_type, observed_at,
                                          stock_status, verification_status, content_hash)
            VALUES (?, ?, 20, 'RECEIPT', CURRENT_TIMESTAMP - interval '5 days', 'IN_STOCK', 'VERIFIED', 'stale-test')
            """, breadId, stale);
        long unpriced = addBranch("No price branch", "boxer", 28.04);
        addBranch("Excluded distant branch", "boxer", 29.0);
        mvc.perform(get("/api/v1/stores/nearby").header("Authorization", token)
                .param("lat", "-26").param("lon", "28").param("r", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(storeId))
                .andExpect(jsonPath("$[0].eligibleProducts").value(1))
                .andExpect(jsonPath("$[1].id").value(stale))
                .andExpect(jsonPath("$[1].distanceKm", org.hamcrest.Matchers.closeTo(2.00, 0.03)))
                .andExpect(jsonPath("$[1].recordedProducts").value(1))
                .andExpect(jsonPath("$[1].recentProducts").value(0))
                .andExpect(jsonPath("$[2].id").value(unpriced))
                .andExpect(jsonPath("$[2].recordedProducts").value(0));
        mvc.perform(get("/api/v1/stores/nearby").header("Authorization", token)
                .param("lat", "-26").param("lon", "28").param("r", "2.5").param("priced", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(stale));
        mvc.perform(get("/api/v1/stores/nearby").header("Authorization", token)
                .param("lat", "-26").param("lon", "28").param("productId", String.valueOf(breadId)).param("sort", "price"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productPrice").value(30));
        mvc.perform(get("/api/v1/stores/" + storeId + "/offers").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].productId").value(breadId))
                .andExpect(jsonPath("$[0].effectivePrice").value(30));
        mvc.perform(get("/api/v1/stores/" + stale + "/observations").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].shelfPrice").value(20));
        mvc.perform(get("/api/v1/stores/" + stale + "/offers").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/stores/" + unpriced).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.distanceKm").doesNotExist());
        mvc.perform(get("/api/v1/stores/nearby").header("Authorization", token).param("lat", "NaN").param("lon", "28"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAdminReviewBeforePublishingBranchProposals() throws Exception {
        String token = register("branch-suggestion@example.test");
        long retailerId = jdbc.queryForObject("SELECT id FROM retailers WHERE slug = 'boxer'", Long.class);
        String payload = json.writeValueAsString(java.util.Map.of("retailerId", retailerId, "name", "Fixture Branch",
                "address", "Fixture address", "city", "Bergville", "province", "KwaZulu-Natal",
                "lat", -28.73, "lon", 29.35, "sourceUrl", "https://example.test/branch"));
        mvc.perform(post("/api/v1/stores/submissions").header("Authorization", token)
                .contentType("application/json").content(payload)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/stores/submissions").header("Authorization", token)
                .contentType("application/json").content(payload)).andExpect(status().isBadRequest());
        long proposalId = jdbc.queryForObject("SELECT id FROM branch_directory_submissions WHERE name = 'Fixture Branch'", Long.class);
        mvc.perform(post("/api/v1/admin/directory/submissions/" + proposalId).param("approve", "true")
                .header("Authorization", token)).andExpect(status().isForbidden());
        String requestPath = "/api/v1/stores/nearby";
        mvc.perform(get(requestPath).header("Authorization", token).param("lat", "-28.73").param("lon", "29.35").param("r", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        var admin = new User();
        admin.setId(1L);
        admin.setEmail("branch-admin@example.test");
        admin.setDisplayName("Branch Admin");
        admin.setRole(Role.ADMIN);
        mvc.perform(post("/api/v1/admin/directory/submissions/" + proposalId).param("approve", "true")
                .header("Authorization", "Bearer " + jwt.issue(admin))).andExpect(status().isOk());
        mvc.perform(get(requestPath).header("Authorization", token).param("lat", "-28.73").param("lon", "29.35").param("r", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Fixture Branch"))
                .andExpect(jsonPath("$[0].sourceUrl").value("https://example.test/branch"));
    }

    @Test
    void persistsCustomItemsIdempotentAddsAndReusableOwnedLists() throws Exception {
        String buyer = register("list-owner@example.test");
        String outsider = register("other-list-owner@example.test");
        String id = newList(buyer, "Weekly groceries");
        String listKey = java.util.UUID.randomUUID().toString();
        String household = "{\"name\":\"Household\"}";
        String other = json.readTree(mvc.perform(post("/api/v1/lists").header("Authorization", buyer)
                .header("Idempotency-Key", listKey).contentType("application/json").content(household))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/lists").header("Authorization", buyer).header("Idempotency-Key", listKey)
                .contentType("application/json").content(household)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(Long.valueOf(other)));
        org.junit.jupiter.api.Assertions.assertNotEquals(id, other);
        String base = "/api/v1/lists/" + id;
        mvc.perform(get("/api/v1/lists").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        String requestKey = java.util.UUID.randomUUID().toString();
        String linked = "{\"productId\":" + breadId + ",\"quantity\":1}";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(base + "/items").header("Authorization", buyer).header("Idempotency-Key", requestKey)
                    .contentType("application/json").content(linked))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.quantity").value(1));
        }
        mvc.perform(post(base + "/items").header("Authorization", buyer).contentType("application/json").content(linked))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.quantity").value(2));
        String custom = """
            {"customName":"Loose apples","quantity":1.25,"unit":"kg","notes":"For lunches","preferredBrand":"Local farm"}
            """;
        mvc.perform(post(base + "/items").header("Authorization", buyer).contentType("application/json").content(custom))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.customName").value("Loose apples"));
        var current = detail(base, buyer);
        long revision = current.path("revision").asLong();
        long breadItem = current.path("items").get(0).path("id").asLong();
        long customItem = current.path("items").get(1).path("id").asLong();
        String itemPath = base + "/items/" + breadItem;
        String edit = """
            {"productId":%d,"quantity":3,"unit":"pack","purchased":true,"notes":"Whole wheat","preferredBrand":"Sasko"}
            """.formatted(breadId);
        mvc.perform(put(itemPath).param("revision", String.valueOf(revision)).header("Authorization", buyer)
                .contentType("application/json").content(edit)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].purchased").value(true))
                .andExpect(jsonPath("$.items[0].combinedMeasure").value(2.1));
        mvc.perform(put(itemPath).param("revision", String.valueOf(revision)).header("Authorization", buyer)
                .contentType("application/json").content(linked)).andExpect(status().isConflict());

        String copyKey = java.util.UUID.randomUUID().toString();
        mvc.perform(post(base + "/duplicate").header("Authorization", buyer).header("Idempotency-Key", copyKey)
                .contentType("application/json")
                .content("{\"name\":\"Next week\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].purchased").value(false))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].preferredBrand").value("Sasko"))
                .andExpect(jsonPath("$.items[1].notes").value("For lunches"));
        mvc.perform(post(base + "/duplicate").header("Authorization", buyer).header("Idempotency-Key", copyKey)
                .contentType("application/json").content("{\"name\":\"Next week\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/lists").header("Authorization", buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get(base).header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(put(base + "/items/" + customItem).param("revision", "0").header("Authorization", outsider)
                .contentType("application/json").content(custom)).andExpect(status().isNotFound());
        mvc.perform(delete(base).header("Authorization", outsider)).andExpect(status().isNotFound());

        changeList(base, buyer, "ARCHIVED");
        mvc.perform(post(base + "/items").header("Authorization", buyer).contentType("application/json").content(linked))
                .andExpect(status().isConflict());
        changeList(base, buyer, "ACTIVE");
        mvc.perform(delete(base + "/items/purchased").param("revision", detail(base, buyer).path("revision").asText())
                .header("Authorization", buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].customName").value("Loose apples"));
    }

    @Test
    void quotesAndPlansOnlyEligibleExactOrApprovedProductsAtTheSelectedLocation() throws Exception {
        String buyer = register("list-estimator@example.test");
        String base = "/api/v1/lists/" + newList(buyer, "Estimate");
        long alternate = jdbc.queryForObject("""
            INSERT INTO products (canonical_name, brand, size_text, category_id)
            SELECT 'Approved Wheat Bread', 'Another brand', '700 g', category_id FROM products WHERE id = ?
            RETURNING id
            """, Long.class, breadId);
        observe("CURRENT_TIMESTAMP - interval '1 hour'", 30, null, "IN_STOCK", "VERIFIED", "primary-list-price");
        jdbc.update("""
            INSERT INTO price_observations (product_id, store_id, shelf_price, source_type, stock_status,
                verification_status, content_hash)
            VALUES (?, ?, 20, 'MANUAL', 'IN_STOCK', 'VERIFIED', 'alternate-list-price')
            """, alternate, storeId);
        mvc.perform(post(base + "/items").header("Authorization", buyer).contentType("application/json")
                .content("{\"productId\":" + breadId + ",\"quantity\":2}")).andExpect(status().isCreated());
        mvc.perform(post(base + "/items").header("Authorization", buyer).contentType("application/json")
                .content("{\"customName\":\"Loose produce\",\"quantity\":1.25,\"unit\":\"kg\"}")).andExpect(status().isCreated());

        mvc.perform(get(base + "/estimate").param("lat", "-26").param("lon", "28").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].total").value(60));
        mvc.perform(get("/api/v1/lists/coverage").param("lat", "-26").param("lon", "28").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].pricedItems").value(1))
                .andExpect(jsonPath("$[0].hasLocation").value(true));
        mvc.perform(get(base + "/estimate").param("lat", "-33").param("lon", "18").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        var state = detail(base, buyer);
        long item = state.path("items").get(0).path("id").asLong();
        mvc.perform(put(base + "/items/" + item).param("revision", state.path("revision").asText())
                .header("Authorization", buyer).contentType("application/json")
                .content("{\"productId\":" + breadId + ",\"quantity\":2,\"flexibleBrand\":true,\"substituteProductId\":" + alternate + "}"))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/estimate").param("lat", "-26").param("lon", "28").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].productId").value(alternate))
                .andExpect(jsonPath("$[0].total").value(40));
        mvc.perform(post(base + "/optimize").header("Authorization", buyer).contentType("application/json")
                .content("{\"lat\":-26,\"lon\":28,\"costPerKm\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skippedItems").value(1))
                .andExpect(jsonPath("$.recommendation.grandTotal").value(40))
                .andExpect(jsonPath("$.recommendation.items[0].substitutedFor").value("Whole Wheat Bread"));
    }

    @Test
    void persistsOwnedBudgetsAndKeepsActualPurchasesSeparateFromPlans() throws Exception {
        String owner = register("budget-owner@example.test");
        String other = register("budget-other@example.test");
        String list = newList(owner, "Grocery basket");
        String second = newList(owner, "Additional basket");
        String foreign = newList(other, "Private basket");
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Johannesburg")).toString();
        String body = """
            {"name":"Weekly groceries","amount":"500.00","period":"WEEKLY","startsOn":"%s","listIds":[%s,%s,%s]}
            """.formatted(today, list, list, second);
        String key = java.util.UUID.randomUUID().toString();
        String base = "/api/v1/budgets/" + budgetPost("/api/v1/budgets", owner, body, key);
        org.junit.jupiter.api.Assertions.assertEquals(base, "/api/v1/budgets/" + budgetPost("/api/v1/budgets", owner, body, key));
        mvc.perform(post("/api/v1/budgets").header("Authorization", owner).contentType("application/json")
                .content(body.replace("[" + list + "," + list + "," + second + "]", "[" + foreign + "]")))
                .andExpect(status().isNotFound());
        mvc.perform(get(base).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/estimate").header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get(base)).andExpect(status().isForbidden());
        mvc.perform(get(base).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.budget.amount").value(500))
                .andExpect(jsonPath("$.listIds.length()").value(2));

        mvc.perform(get(base + "/estimate").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assessment.status").value("EMPTY"));
        String expense = """
            {"description":"Receipt","category":"Dairy","amount":"472.19","purchasedOn":"%s","listId":%s,"branchId":%d}
            """.formatted(today, list, storeId);
        String expenseKey = java.util.UUID.randomUUID().toString();
        String expenseId = budgetPost(base + "/expenses", owner, expense, expenseKey);
        org.junit.jupiter.api.Assertions.assertEquals(expenseId, budgetPost(base + "/expenses", owner, expense, expenseKey));
        mvc.perform(get(base).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actualSpent").value(472.19))
                .andExpect(jsonPath("$.actualRemaining").value(27.81)).andExpect(jsonPath("$.expenseCount").value(1))
                .andExpect(jsonPath("$.spendingByCategory[0].total").value(472.19))
                .andExpect(jsonPath("$.expenses[0].branchName").value("Test Checkers"));
        mvc.perform(put(base + "/expenses/" + expenseId).param("revision", "0").header("Authorization", owner)
                .contentType("application/json").content(expense.replace("472.19", "450.01"))).andExpect(status().isNoContent());
        mvc.perform(put(base + "/expenses/" + expenseId).param("revision", "0").header("Authorization", owner)
                .contentType("application/json").content(expense)).andExpect(status().isConflict());
        mvc.perform(delete(base + "/expenses/" + expenseId).param("revision", "1").header("Authorization", other))
                .andExpect(status().isNotFound());
        mvc.perform(put(base).param("revision", "0").header("Authorization", owner)
                .contentType("application/json").content(body.replace("500.00", "1500.00"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/budgets").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(1500)).andExpect(jsonPath("$[0].actualRemaining").value(1049.99));
        mvc.perform(post(base + "/archive").param("revision", "1").header("Authorization", owner))
                .andExpect(status().isNoContent());
        mvc.perform(get(base + "/estimate").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot").value(true)).andExpect(jsonPath("$.assessment.status").value("EMPTY"));

        String copied = "/api/v1/budgets/" + budgetPost(base + "/reuse", owner,
                "{\"name\":\"Next week\",\"startsOn\":\"" + today + "\"}", java.util.UUID.randomUUID().toString());
        mvc.perform(get(copied).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.budget.amount").value(1500)).andExpect(jsonPath("$.listIds.length()").value(2))
                .andExpect(jsonPath("$.expenseCount").value(0));
        mvc.perform(post(copied + "/expenses").header("Authorization", owner).header("Idempotency-Key", expenseKey)
                .contentType("application/json").content(expense)).andExpect(status().isConflict());
        mvc.perform(delete(copied).param("revision", "0").header("Authorization", owner)).andExpect(status().isNoContent());
    }

    @Test
    void qualifiesBudgetCoveragePromotionsQuantitiesAndLocation() throws Exception {
        String owner = register("budget-prices@example.test");
        String listId = newList(owner, "Bread and more");
        String list = "/api/v1/lists/" + listId;
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Johannesburg")).toString();
        String base = "/api/v1/budgets/" + budgetPost("/api/v1/budgets", owner,
                """
                {"name":"Trip","amount":500,"period":"TRIP","startsOn":"%s","listIds":[%s]}
                """.formatted(today, listId), java.util.UUID.randomUUID().toString());
        observe("CURRENT_TIMESTAMP - interval '1 hour'", 300, null, "IN_STOCK", "VERIFIED", "budget-bread");
        jdbc.update("""
            INSERT INTO promotions (product_id,store_id,promo_type,promo_price,min_quantity,start_date,end_date,status)
            VALUES (?,?,'MULTIBUY',450,2,CURRENT_DATE-1,CURRENT_DATE+1,'ACTIVE'),
                   (?,?,'DISCOUNT',1,1,CURRENT_DATE-7,CURRENT_DATE-2,'ACTIVE')
            """, breadId, storeId, breadId, storeId);
        mvc.perform(post(list + "/items").header("Authorization", owner).contentType("application/json")
                .content("{\"productId\":" + breadId + ",\"quantity\":2}")).andExpect(status().isCreated());
        var local = get(base + "/estimate").header("Authorization", owner).param("lat", "-26").param("lon", "28");
        mvc.perform(local).andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment.status").value("WITHIN_BUDGET"))
                .andExpect(jsonPath("$.assessment.knownSubtotal").value(450))
                .andExpect(jsonPath("$.lines[0].promotionEndsOn").exists());
        mvc.perform(post(list + "/items").header("Authorization", owner).contentType("application/json")
                .content("{\"customName\":\"Loose produce\",\"quantity\":1,\"unit\":\"kg\"}")).andExpect(status().isCreated());
        mvc.perform(local).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.assessment.remainingAfterKnown").value(50))
                .andExpect(jsonPath("$.assessment.unpriced").value(1)).andExpect(jsonPath("$.lines[1].total").doesNotExist());
        mvc.perform(get(base + "/estimate").header("Authorization", owner).param("lat", "-33").param("lon", "18"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assessment.status").value("INSUFFICIENT_PRICES"))
                .andExpect(jsonPath("$.assessment.knownSubtotal").doesNotExist());
        var state = detail(list, owner);
        mvc.perform(put(list + "/items/" + state.path("items").get(0).path("id").asText())
                .param("revision", state.path("revision").asText()).header("Authorization", owner).contentType("application/json")
                .content("{\"productId\":" + breadId + ",\"quantity\":3}")).andExpect(status().isOk());
        mvc.perform(local).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.status").value("OVER_BUDGET"))
                .andExpect(jsonPath("$.assessment.knownSubtotal").value(750))
                .andExpect(jsonPath("$.assessment.overBy").value(250));
        jdbc.update("UPDATE promotions SET start_date=CURRENT_DATE-3,end_date=CURRENT_DATE-1 WHERE product_id=?", breadId);
        mvc.perform(local).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.knownSubtotal").value(900))
                .andExpect(jsonPath("$.lines[0].promotionEndsOn").doesNotExist());
    }

    private String budgetPost(String path, String token, String body, String key) throws Exception {
        return json.readTree(mvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asText();
    }


    private String newList(String token, String name) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("name", name, "description", "Planning", "budgetAmount", 100));
        return json.readTree(mvc.perform(post("/api/v1/lists").header("Authorization", token)
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asText();
    }

    private com.fasterxml.jackson.databind.JsonNode detail(String path, String token) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void changeList(String base, String token, String status) throws Exception {
        mvc.perform(put(base).param("revision", detail(base, token).path("revision").asText())
                .param("status", status).header("Authorization", token).contentType("application/json")
                .content("{\"name\":\"Updated weekly\",\"description\":\"New description\",\"budgetAmount\":120}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(status));
    }

    private long addBranch(String name, String slug, double lon) {
        return jdbc.queryForObject("""
            INSERT INTO stores (retailer_id, name, location)
            SELECT id, ?, ST_SetSRID(ST_MakePoint(?, -26), 4326)
            FROM retailers WHERE slug = ? RETURNING id
            """, Long.class, name, lon, slug);
    }

    private String register(String email) throws Exception {
        String result = mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                    .content("{\"email\":\"" + email + "\",\"password\":\"local-test-only\",\"displayName\":\"Buyer\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(result).path("token").asText();
    }

    private ResultActions search(String query) throws Exception {
        return mvc.perform(get("/api/v1/products/search").param("q", query)).andExpect(status().isOk());
    }

    private ResultActions nearbySearch(String priced) throws Exception {
        return mvc.perform(get("/api/v1/products/search").param("q", "bread")
                    .param("lat", "-26").param("lon", "28").param("priced", priced))
                .andExpect(status().isOk());
    }

    private ResultActions prices() throws Exception {
        return mvc.perform(get("/api/v1/products/" + breadId + "/prices").param("lat", "-26").param("lon", "28"))
                .andExpect(status().isOk());
    }

    private void observe(String timestampExpression, Integer shelf, Integer promo, String stock, String verification, String hash) {
        jdbc.update("""
            INSERT INTO price_observations (product_id, store_id, source_type, observed_at,
                shelf_price, promo_price, stock_status, verification_status, content_hash)
            VALUES (?, ?, 'MANUAL', %s, ?, ?, ?, ?, ?)
            """.formatted(timestampExpression), breadId, storeId, shelf, promo, stock, verification, hash);
    }
}
