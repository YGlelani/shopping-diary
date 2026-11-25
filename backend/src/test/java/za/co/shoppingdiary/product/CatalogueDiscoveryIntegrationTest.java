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
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JwtService jwt;
    @Autowired SampleRetailerScraper scraper;
    @Autowired CsvImportService csv;
    @Autowired za.co.shoppingdiary.ingestion.automation.ScopedPriceIntake intake;
    @Autowired za.co.shoppingdiary.ingestion.automation.LoyaltyHubQuota freeQuota;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(22));
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
                .andExpect(jsonPath("$.recommendation.grandTotal").doesNotExist())
                .andExpect(jsonPath("$.recommendation.knownCost").value(40))
                .andExpect(jsonPath("$.recommendation.unresolved[0].name").value("Loose produce"))
                .andExpect(jsonPath("$.recommendation.items[0].substitutedFor").value("Whole Wheat Bread"));
        mvc.perform(post(base + "/optimize").header("Authorization", buyer).contentType("application/json")
                .content("{\"lat\":-26,\"lon\":28,\"travelMode\":\"WALK\",\"rejectedAlternatives\":[" + item + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.knownCost").value(60));
        jdbc.update("UPDATE products SET size_text='500 g' WHERE id=?", alternate);
        entityManager.clear();
        mvc.perform(post(base + "/optimize").header("Authorization", buyer).contentType("application/json")
                .content("{\"lat\":-26,\"lon\":28,\"travelMode\":\"WALK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.knownCost").value(60));

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

    @Test
    void comparesTripsAndPreservesTheSelectedSnapshot() throws Exception {
        String owner = register("optimizer-owner@example.test");
        String stranger = register("optimizer-stranger@example.test");
        String listId = newList(owner, "Optimisation test");
        String path = "/api/v1/lists/" + listId;
        var otherBranch = addBranch("Second branch", "shoprite", 28.01);
        long milk = jdbc.queryForObject("SELECT id FROM products WHERE canonical_name='Full Cream Milk'",Long.class);
        jdbc.update("""
            INSERT INTO price_observations(product_id,store_id,shelf_price,source_type,stock_status,verification_status,content_hash)
            VALUES (?, ?, 30, 'MANUAL','IN_STOCK','VERIFIED','optimizer-own-bread'),
                   (?, ?, 40, 'MANUAL','IN_STOCK','VERIFIED','optimizer-other-bread'),
                   (?, ?, 60, 'MANUAL','IN_STOCK','VERIFIED','optimizer-own-milk'),
                   (?, ?, 20, 'MANUAL','IN_STOCK','VERIFIED','optimizer-other-milk')
            """,breadId,storeId,breadId,otherBranch,milk,storeId,milk,otherBranch);
        for (long product : new long[]{breadId,milk}) {
            mvc.perform(post(path+"/items").header("Authorization",owner).contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("productId",product,"quantity",1))))
                    .andExpect(status().isCreated());
        }
        String request = """
            {"lat":-26,"lon":28,"radiusKm":15,"maxStores":2,"travelMode":"FIXED","tripCost":5,"budgetAmount":60}
            """;
        var quote = json.readTree(mvc.perform(post(path+"/optimize").header("Authorization",owner).contentType("application/json")
                .content(request.replace(":5,",":5.00,").replace(":60}",":60.00}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.bestSingleStore.grandTotal").value(65))
                .andExpect(jsonPath("$.recommendation.strategy").value("SPLIT"))
                .andExpect(jsonPath("$.recommendation.totalProductCost").value(50))
                .andExpect(jsonPath("$.recommendation.grandTotal").value(55))
                .andExpect(jsonPath("$.recommendation.budgetStatus").value("WITHIN_BUDGET"))
                .andExpect(jsonPath("$.recommendation.items[0].observationId").exists())
                .andExpect(jsonPath("$.savingsVsBestSingle").value(10))
                .andReturn().getResponse().getContentAsString());
        String save = json.writeValueAsString(java.util.Map.of("listId",Long.valueOf(listId),"name","My shopping plan",
                "strategy","SPLIT","fingerprint",quote.path("bestSplit").path("fingerprint").asText(),"assumptions",json.readTree(request)));
        String key = java.util.UUID.randomUUID().toString();
        String id = budgetPost("/api/v1/shopping-plans",owner,save,key);
        org.junit.jupiter.api.Assertions.assertEquals(id,budgetPost("/api/v1/shopping-plans",owner,save,key));
        String saved = "/api/v1/shopping-plans/" + id;
        mvc.perform(get(saved).header("Authorization",stranger)).andExpect(status().isNotFound());
        mvc.perform(post(path+"/optimize").header("Authorization",stranger).contentType("application/json").content(request))
                .andExpect(status().isNotFound());
        mvc.perform(get(saved).header("Authorization",owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estimate.grandTotal").value(55)).andExpect(jsonPath("$.assumptions.tripCost").value(5));
        jdbc.update("UPDATE price_observations SET shelf_price=24 WHERE content_hash='optimizer-other-milk'");
        mvc.perform(post("/api/v1/shopping-plans").header("Authorization",owner).header("Idempotency-Key",java.util.UUID.randomUUID())
                .contentType("application/json").content(save)).andExpect(status().isConflict());
        mvc.perform(get(saved).header("Authorization",owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estimate.grandTotal").value(55));
        mvc.perform(post(path+"/optimize").header("Authorization",owner).contentType("application/json")
                .content(request.replace("FIXED","UNKNOWN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.grandTotal").doesNotExist())
                .andExpect(jsonPath("$.recommendation.knownCost").value(54))
                .andExpect(jsonPath("$.recommendation.budgetStatus").value("TRANSPORT_UNKNOWN"))
                .andExpect(jsonPath("$.savingsVsBestSingle").doesNotExist());
        mvc.perform(post(path+"/optimize").header("Authorization",owner).contentType("application/json")
                .content(request.replace("maxStores\":2","maxStores\":1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.stores.length()").value(1));
        long excluded = jdbc.queryForObject("SELECT retailer_id FROM stores WHERE id=?",Long.class,otherBranch);
        var exclusions = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(request);
        exclusions.putArray("excludedRetailers").add(excluded);
        mvc.perform(post(path+"/optimize").header("Authorization",owner).contentType("application/json").content(exclusions.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.grandTotal").value(95))
                .andExpect(jsonPath("$.recommendation.budgetStatus").value("OVER_BUDGET"));
        jdbc.update("UPDATE price_observations SET stock_status='OUT_OF_STOCK' WHERE content_hash LIKE 'optimizer-%-milk'");
        mvc.perform(post(path+"/optimize").header("Authorization",owner).contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.unresolved[0].reason").value("OUT_OF_STOCK_OBSERVATION"));
        mvc.perform(post(path+"/optimize").header("Authorization",owner).contentType("application/json")
                .content(request.replace("28,","18,"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.budgetStatus").value("NO_FEASIBLE_PLAN"))
                .andExpect(jsonPath("$.recommendation.unresolved.length()").value(2))
                .andExpect(jsonPath("$.recommendation.knownCost").doesNotExist());
    }

    @Test
    void sharesPromotionTotalsAcrossDiscoveryListsBudgetsAndOptimisation() throws Exception {
        String buyer = register("specials-buyer@example.test");
        String outsider = register("specials-other@example.test");
        String id = newList(buyer,"Specials basket");
        String list = "/api/v1/lists/" + id;
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Johannesburg"));
        observe("CURRENT_TIMESTAMP - interval '5 minutes'",30,null,"IN_STOCK","VERIFIED","specials-shelf");
        long deal = jdbc.queryForObject("""
            INSERT INTO promotions(product_id,store_id,promo_type,title,promo_price,min_quantity,start_date,end_date,status)
            VALUES (?,?,'MULTIBUY','Bread two-pack',50,2,?,?,'SCHEDULED') RETURNING id
            """,Long.class,breadId,storeId,today.minusDays(1),today);
        jdbc.update("""
            INSERT INTO promotions(product_id,store_id,promo_type,title,promo_price,min_quantity,start_date,end_date,status)
            VALUES (?,?,'DISCOUNT','Tomorrow',1,1,?,?,'SCHEDULED'),(?,?,'DISCOUNT','Expired',1,1,?,?,'ACTIVE')
            """,breadId,storeId,today.plusDays(1),today.plusDays(5),breadId,storeId,today.minusDays(4),today.minusDays(1));
        mvc.perform(get("/api/v1/specials").param("lat","-26").param("lon","28").param("q","Sasko 700 g").param("sort","PRICE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(deal)).andExpect(jsonPath("$[0].qualification").value("QUANTITY_REQUIRED"))
                .andExpect(jsonPath("$[0].saving").doesNotExist());
        mvc.perform(get("/api/v1/specials").param("lat","-33").param("lon","18").param("q","Sasko"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/specials/" + deal).param("quantity","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offeredTotal").value(50))
                .andExpect(jsonPath("$.shelfTotal").value(60)).andExpect(jsonPath("$.saving").value(10));
        mvc.perform(post(list+"/items").header("Authorization",buyer).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("productId",breadId,"quantity",1)))).andExpect(status().isCreated());
        mvc.perform(get(list+"/specials").header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(get(list+"/specials").header("Authorization",buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].qualification").value("QUANTITY_REQUIRED"));
        var state = detail(list,buyer);
        long item = state.path("items").get(0).path("id").asLong();
        mvc.perform(put(list+"/items/"+item).param("revision",state.path("revision").asText())
                .header("Authorization",buyer).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("productId",breadId,"quantity",2))))
                .andExpect(status().isOk());
        mvc.perform(get(list+"/specials").header("Authorization",buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].qualification").value("QUALIFIES")).andExpect(jsonPath("$[0].quantity").value(2));
        mvc.perform(get("/api/v1/products/"+breadId+"/prices").param("lat","-26").param("lon","28").param("quantity","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].totalPrice").value(50));
        mvc.perform(get(list+"/estimate").header("Authorization",buyer).param("lat","-26").param("lon","28"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].total").value(50));
        String budget = budgetPost("/api/v1/budgets",buyer,
                """
                {"name":"Specials trip","amount":55,"period":"TRIP","startsOn":"%s","listIds":[%s]}
                """.formatted(today,id),java.util.UUID.randomUUID().toString());
        var budgetQuote = get("/api/v1/budgets/"+budget+"/estimate").header("Authorization",buyer).param("lat","-26").param("lon","28");
        mvc.perform(budgetQuote).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.knownSubtotal").value(50));
        var trip = json.createObjectNode().put("lat",-26).put("lon",28).put("travelMode","WALK").put("budgetId",Long.parseLong(budget));
        mvc.perform(post(list+"/optimize").header("Authorization",buyer).contentType("application/json").content(trip.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.grandTotal").value(50))
                .andExpect(jsonPath("$.recommendation.items[0].promotionId").value(deal));
        mvc.perform(post(list+"/optimize").header("Authorization",buyer).contentType("application/json")
                .content(trip.put("ignorePromotions",true).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.grandTotal").value(60));
        jdbc.update("UPDATE promotions SET status='EXPIRED' WHERE id=?",deal);
        mvc.perform(budgetQuote).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.knownSubtotal").value(60))
                .andExpect(jsonPath("$.assessment.status").value("OVER_BUDGET"));
    }

    @Test
    void moderatesNationalPromotionsAndEnforcesLoyaltyConfirmation() throws Exception {
        String buyer = register("specials-admin@example.test");
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email='specials-admin@example.test'",Long.class);
        var admin = new User();
        admin.setId(adminId); admin.setEmail("specials-admin@example.test");
        admin.setDisplayName("Promotion Admin"); admin.setRole(Role.ADMIN);
        String token = "Bearer " + jwt.issue(admin);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Johannesburg"));
        long distant = addBranch("Distant Checkers","checkers",30.0);
        long retailer = jdbc.queryForObject("SELECT retailer_id FROM stores WHERE id=?",Long.class,storeId);
        jdbc.update("""
            INSERT INTO price_observations(product_id,store_id,shelf_price,source_type,stock_status,verification_status,content_hash)
            VALUES (?,?,30,'MANUAL','IN_STOCK','VERIFIED','specials-member-shelf')
            """,breadId,distant);
        String body = """
            {"productId":%d,"storeId":%d,"kind":"MEMBER","scope":"NATIONAL","title":"Member bread","description":"Loyalty card required",
             "loyaltyProgram":"Confirmed Checkers programme","price":18,"minimumQuantity":1,"startsOn":"%s","endsOn":"%s",
             "verification":"VERIFIED","sourceReference":"https://example.test/brochure"}
            """.formatted(breadId,storeId,today,today.plusDays(2));
        String adminPath = "/api/v1/admin/specials";
        mvc.perform(post(adminPath).header("Authorization",buyer).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        var saved = mvc.perform(post(adminPath).header("Authorization",token).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long deal = json.readTree(saved).path("id").asLong();
        mvc.perform(get("/api/v1/specials/"+deal)).andExpect(status().isNotFound());
        mvc.perform(get(adminPath).param("validity","PENDING").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].validity").value("PENDING"));
        mvc.perform(post(adminPath+"/"+deal+"/review").header("Authorization",token).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("status","ACTIVE","revision",0)))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/specials").param("lat","-26").param("lon","30").param("branchId",String.valueOf(distant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].scope").value("NATIONAL"))
                .andExpect(jsonPath("$[0].qualification").value("LOYALTY_REQUIRED"));
        mvc.perform(get("/api/v1/specials/"+deal).param("branchId",String.valueOf(distant)).param("memberConfirmed","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saving").value(12));
        String path="/api/v1/lists/"+newList(buyer,"Member basket");
        mvc.perform(post(path+"/items").header("Authorization",buyer).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("productId",breadId,"quantity",1)))).andExpect(status().isCreated());
        var trip=json.createObjectNode().put("lat",-26).put("lon",30).put("travelMode","WALK");
        mvc.perform(post(path+"/optimize").header("Authorization",buyer).contentType("application/json").content(trip.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.grandTotal").value(30));
        trip.putArray("confirmedLoyaltyRetailers").add(retailer);
        mvc.perform(post(path+"/optimize").header("Authorization",buyer).contentType("application/json")
                .content(trip.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.grandTotal").value(18));
        mvc.perform(get(adminPath+"/"+deal+"/audit").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].actorId").value(adminId)).andExpect(jsonPath("$[0].action").value("ACTIVE"));
        mvc.perform(post("/api/v1/admin/ingest/reefapi/promotions").param("retailerSlug","picknpay")
                .header("Authorization",token)).andExpect(status().isServiceUnavailable());
        mvc.perform(get(adminPath+"/imports").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("FAILED"));
        mvc.perform(get("/api/v1/specials/coverage")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(20)));
    }

    @Test
    void calculatesPromotionRulesWithoutStackingAndRejectsUnqualifiedConditions() {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
            SELECT promotion_quantity_total('PERCENT',49.99,3,NULL,1,20,NULL,NULL,false)
            """,java.math.BigDecimal.class)).isEqualByComparingTo("119.97");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
            SELECT promotion_quantity_total('BOGOF',30,7,NULL,2,NULL,1,NULL,false)
            """,java.math.BigDecimal.class)).isEqualByComparingTo("150.00");
        for (String sql : new String[]{
                "SELECT promotion_quantity_total('BOGOF',30,2,NULL,2,NULL,1,NULL,false)",
                "SELECT promotion_quantity_total('MULTIBUY',30,1,50,2,NULL,NULL,NULL,false)",
                "SELECT promotion_quantity_total('PERCENT',NULL,2,NULL,1,10,NULL,NULL,false)",
                "SELECT promotion_quantity_total('COUPON',30,2,2,1,NULL,NULL,NULL,true)",
                "SELECT promotion_quantity_total('BUNDLE',30,2,20,1,NULL,NULL,NULL,true)"}) {
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(sql,java.math.BigDecimal.class)).isNull();
        }
        observe("CURRENT_TIMESTAMP - interval '5 minutes'",30,null,"IN_STOCK","VERIFIED","specials-overlap");
        jdbc.update("""
            INSERT INTO promotions(product_id,store_id,promo_type,promo_price,min_quantity,start_date,end_date,status)
            VALUES (?,?,'MULTIBUY',50,2,CURRENT_DATE-1,CURRENT_DATE+1,'ACTIVE'),
                   (?,?,'DISCOUNT',24,1,CURRENT_DATE-1,CURRENT_DATE+1,'ACTIVE')
            """,breadId,storeId,breadId,storeId);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT total FROM branch_quantity_quote(?,?,7,3)",
                java.math.BigDecimal.class,breadId,storeId)).isEqualByComparingTo("72.00");
    }


    @Test
    void dryRunsAndDeduplicatesCatalogueAndOsmExports() throws Exception {
        String ordinary = register("automated-import-admin@example.test");
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,"automated-import-admin@example.test");
        var admin=new User();
        admin.setId(adminId); admin.setEmail("automated-import-admin@example.test");
        admin.setDisplayName("Import Admin"); admin.setRole(Role.ADMIN);
        String authorized="Bearer "+jwt.issue(admin);
        String base="/api/v1/admin/grocery-automation";
        byte[] source=("""
            {"code":"6000000009874","product_name":"Catalogue test food","brands":"Fixture","quantity":"1 kg","countries_tags":["en:south-africa"],"categories_tags":["en:rices"]}
            {"code":"6000000009875","product_name":"Outside scope","countries_tags":["en:france"]}
            """).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var export=new MockMultipartFile("file","catalogue.jsonl","application/x-ndjson",source);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(base+"/OPEN_FOOD_FACTS/file")
                .file(export).header("Authorization",ordinary)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(base+"/OPEN_FOOD_FACTS/file")
                .file(export).header("Authorization",authorized)).andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1)).andExpect(jsonPath("$.dryRun").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE barcode='6000000009874'",Integer.class)).isZero();
        for (int i=0;i<2;i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(base+"/OPEN_FOOD_FACTS/file")
                    .file(export).param("dryRun","false").header("Authorization",authorized)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(1-i));
        }
        String xml="""
            <osm version="0.6">
              <node id="9000000001" lat="-30.02" lon="27.01">
                <tag k="shop" v="supermarket"/><tag k="brand" v="Pick n Pay"/><tag k="name" v="Fixture Pick n Pay"/>
              </node>
              <node id="9000000002" lat="-30.03" lon="27.02" timestamp="2016-03-01T12:00:00Z">
                <tag k="shop" v="supermarket"/><tag k="brand" v="Pick n Pay"/><tag k="name" v="Older Fixture"/>
              </node>
            </osm>
            """;
        var osm=new MockMultipartFile("file","supermarkets.osm","application/xml",xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int i=0;i<2;i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(base+"/OPEN_STREET_MAP/file")
                    .file(osm).param("dryRun","false").header("Authorization",authorized)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(2*(1-i)));
        }
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT active FROM stores WHERE osm_type='node' AND osm_id=9000000002",Boolean.class)).isFalse();
        mvc.perform(get(base).header("Authorization",authorized)).andExpect(status().isOk())
                .andExpect(jsonPath("$.review[0].reason").value("OSM object has not been edited in five years"));
        mvc.perform(get("/api/v1/products/search").param("q","6000000009874")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canonicalName").value("Catalogue test food"));
        mvc.perform(get("/api/v1/stores/nearby").param("lat","-30.02").param("lon","27.01").param("r","2").header("Authorization",ordinary))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Fixture Pick n Pay"));
    }

    @Test
    void savesScopedObservationsWithoutInventingBranchPrices() throws Exception {
        String token=register("scope-review@example.test");
        jdbc.update("UPDATE stores SET osm_type='node',osm_id=9000000003 WHERE id=?",storeId);
        var amount=new java.math.BigDecimal("22.90");
        var online=new za.co.shoppingdiary.ingestion.automation.PriceDataSource.Price(null,"0012345678901","fixture-700",
                "Whole Wheat Bread","Sasko","700 g","checkers","ONLINE","online",null,null,amount,amount,null,null,
                "ZAR",java.time.Instant.now().minusSeconds(3600),null,"https://example.test/grocery",null,null,null);
        var historical=new za.co.shoppingdiary.ingestion.automation.PriceDataSource.Price("fixture-historic","0012345678901",null,
                null,null,null,null,"BRANCH","Test Checkers","node",9000000003L,amount,null,null,null,"ZAR",null,
                java.time.LocalDate.of(2025,8,12),"https://prices.openfoodfacts.org/",null,null,null);
        for (int i=0;i<2;i++) {
            intake.accept("LOYALTYHUB",java.util.List.of(online),false);
            intake.accept("OPEN_PRICES",java.util.List.of(historical),false);
        }
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM grocery_scoped_prices WHERE product_id=?",Integer.class,breadId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM loyalty_latest_prices WHERE in_stock IS NULL",
                Integer.class)).isGreaterThanOrEqualTo(1);
        prices().andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/scoped-prices/"+breadId).header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].source").value("OPEN_PRICES")).andExpect(jsonPath("$[0].observedOn").value("2025-08-12"))
                .andExpect(jsonPath("$[0].observedAt").doesNotExist()).andExpect(jsonPath("$[0].historical").value(true));
    }


    @Test
    void stopsAtProviderQuotaWithoutWritingPrices() {
        var quotaState=freeQuota.usage();
        int observations = jdbc.queryForObject("SELECT count(*) FROM grocery_scoped_prices",Integer.class);
        try {
            freeQuota.record(0,0,null,java.time.Instant.now().plusSeconds(3600));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> freeQuota.reserve(false,10))
                    .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            failure -> org.assertj.core.api.Assertions.assertThat(failure.getStatusCode().value()).isEqualTo(429));
            org.assertj.core.api.Assertions.assertThat(freeQuota.usage().localCalls()).isEqualTo(quotaState.localCalls());
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM grocery_scoped_prices",Integer.class)).isEqualTo(observations);
        } finally {
            var independent = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(status -> jdbc.update("""
                UPDATE loyalty_hub_free_state SET remaining=?,rows_remaining=?,retry_after=?,quota_reset=?,last_checked_at=?
                WHERE singleton
                """,quotaState.remaining(),quotaState.rowsRemaining(),quotaState.retryAfter(),quotaState.quotaReset(),quotaState.lastCheckedAt()));
        }
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

    @Test
    void replacesLatestCataloguePriceAndKeepsRegionalOffersSeparate() throws Exception {
        var now=java.time.Instant.now();
        var metadata=json.readTree("""
            {"image_url":"https://retailer.example/bread.jpg","size":{"quantity":0.7,"unit":"kg"},
             "unit_price":{"value":24.27,"per":"kg"},"department":"bakery","category":"Bread"}
            """);
        var initial=catalogueFixture("ONLINE","online","22.90",now.minusSeconds(600),metadata);
        var latest=catalogueFixture("ONLINE","online","16.99",now.minusSeconds(200),metadata);
        var regional=catalogueFixture("REGIONAL","gauteng","18.99",now.minusSeconds(200),metadata);
        intake.accept("LOYALTYHUB",java.util.List.of(initial,latest,regional,initial,latest),false);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList("""
                SELECT amount FROM loyalty_latest_prices WHERE product_id=? ORDER BY scope
                """,java.math.BigDecimal.class,breadId)).containsExactly(
                        new java.math.BigDecimal("16.99"),new java.math.BigDecimal("18.99"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT attributes->'unit_price'->>'per' FROM loyalty_product_metadata WHERE product_id=?
                """,String.class,breadId)).isEqualTo("kg");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT image_url FROM products WHERE id=?",String.class,breadId)).isNull();
        prices().andExpect(jsonPath("$.length()").value(0));
    }

    private za.co.shoppingdiary.ingestion.automation.PriceDataSource.Price catalogueFixture(
            String scope,String region,String value,java.time.Instant at,com.fasterxml.jackson.databind.JsonNode attributes) {
        var amount=new java.math.BigDecimal(value);
        return new za.co.shoppingdiary.ingestion.automation.PriceDataSource.Price(null,"0012345678901","fixture-latest",
                "Whole Wheat Bread","Sasko","700 g","checkers",scope,region,null,null,amount,amount,null,null,
                "ZAR",at,null,null,null,null,true,attributes);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LOYALTYHUB_LIVE_TEST", matches = "true")
    void importsRealMaizeMealIntoIsolatedDatabase() {
        var key=System.getenv("LOYALTYHUB_API_KEY");
        org.assertj.core.api.Assertions.assertThat(key!=null && !key.isBlank()).isTrue();
        var source=context.getBean(za.co.shoppingdiary.ingestion.automation.LoyaltyHubSource.class);
        var originalKey=org.springframework.test.util.ReflectionTestUtils.getField(source,"key");
        var originalEnabled=org.springframework.test.util.ReflectionTestUtils.getField(source,"optedIn");
        var originalSize=org.springframework.test.util.ReflectionTestUtils.getField(source,"pageSize");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(source,"key",key);
            org.springframework.test.util.ReflectionTestUtils.setField(source,"optedIn",true);
            org.springframework.test.util.ReflectionTestUtils.setField(source,"pageSize",1);
            var page=source.collect("maize meal",0,null,true);
            org.assertj.core.api.Assertions.assertThat(page.rows()).hasSize(1);
            intake.accept("LOYALTYHUB",page.rows(),false);
            int products=jdbc.queryForObject("SELECT count(*) FROM loyalty_product_metadata",Integer.class);
            int prices=jdbc.queryForObject("SELECT count(*) FROM loyalty_latest_prices",Integer.class);
            int images=jdbc.queryForObject("SELECT count(*) FROM loyalty_product_metadata WHERE attributes->>'image_url' IS NOT NULL",Integer.class);
            var usage=freeQuota.usage();
            System.out.printf("Loyalty Hub isolated live test: %d products, %d latest offers, %d supplied image URLs, %s provider requests remaining, %s provider rows remaining.%n",
                    products,prices,images,usage.remaining(),usage.rowsRemaining());
            org.assertj.core.api.Assertions.assertThat(products).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(prices).isEqualTo(1);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(source,"key",originalKey);
            org.springframework.test.util.ReflectionTestUtils.setField(source,"optedIn",originalEnabled);
            org.springframework.test.util.ReflectionTestUtils.setField(source,"pageSize",originalSize);
        }
    }

    @Test
    void discoveryPersistsBackoffAndSupportsPause() {
        var automation=context.getBean(za.co.shoppingdiary.ingestion.automation.GroceryAutomation.class);
        org.assertj.core.api.Assertions.assertThat(automation.discovery()).hasSize(72);
        var run=automation.discoverNext();
        org.assertj.core.api.Assertions.assertThat(run.status()).isEqualTo("FAILED");
        var failed=automation.discovery().stream().filter(item -> item.failures()>0).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(failed.nextRun()).isAfter(java.time.OffsetDateTime.now());
        automation.pause(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(automation::discoverNext)
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode().value()).isEqualTo(409));
    }
}
