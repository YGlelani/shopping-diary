package za.co.shoppingdiary.ingestion.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoyaltyHubSourceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsRegionalLoyaltyAndPackagingFields() throws Exception {
        var payload = json.readTree("""
            {"data":[{"name":"Maize Meal 5 kg","gtin":"6000000000000","retailer":"checkers","retailer_sku":"A1",
              "price":46.99,"was_price":50.99,"loyalty_price":44.99,"on_promotion":true,"promotion_loyalty":true,
              "currency":"ZAR","region":"gauteng","price_basis":"regional_store","image_url":"https://example.com/meal.jpg",
              "size":{"quantity":5,"unit":"kg"},"last_seen_at":"2026-11-25T08:00:00Z","unit_price":{"value":9.40,"per":"kg"}}],
             "meta":{"has_more":true,"next_offset":10,"updated_at":"2026-11-25T08:01:00Z"}}
            """);
        var page=LoyaltyHubSource.pricePage(payload.path("data"),payload.path("meta"),0);
        assertThat(page.nextOffset()).isEqualTo(10);
        var price=page.rows().getFirst();
        assertThat(price.barcode()).isEqualTo("6000000000000");
        assertThat(price.scope()).isEqualTo("REGIONAL");
        assertThat(price.scopeLabel()).isEqualTo("gauteng");
        assertThat(price.promotional()).isEqualByComparingTo(new BigDecimal("46.99"));
        assertThat(price.member()).isEqualByComparingTo(new BigDecimal("44.99"));
        assertThat(price.attributes().path("size").path("quantity").asInt()).isEqualTo(5);
        assertThat(price.attributes().path("image_url").asText()).isEqualTo("https://example.com/meal.jpg");
    }

    @Test
    void rejectsMalformedPricesAndNonAdvancingPagination() throws Exception {
        var invalidRows=json.readTree("[null]");
        var invalidCursor=json.readTree("{\"has_more\":true,\"next_offset\":10}");
        assertThatThrownBy(() -> LoyaltyHubSource.pricePage(invalidRows,json.createObjectNode(),0))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> LoyaltyHubSource.pricePage(json.createArrayNode(),invalidCursor,10))
                .isInstanceOf(ResponseStatusException.class);
    }
}
