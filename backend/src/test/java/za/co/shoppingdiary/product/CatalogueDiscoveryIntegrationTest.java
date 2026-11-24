package za.co.shoppingdiary.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DISCOVERY_POSTGIS_TEST", matches = "true")
@SpringBootTest(properties = {
    "spring.config.import=",
    "logging.level.root=WARN",
    "logging.level.za.co.shoppingdiary=INFO",
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55436/discovery_test",
    "spring.datasource.username=postgres",
    "spring.datasource.password=discovery_test",
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
                   ('Orange Juice 750ml','Test Juice', NULL,'Beverages', NULL, NULL)
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
