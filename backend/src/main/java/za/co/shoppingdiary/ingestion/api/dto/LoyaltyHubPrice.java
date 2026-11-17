package za.co.shoppingdiary.ingestion.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class LoyaltyHubPrice {
    private String retailer;
    private String barcode;
    private String name;
    private String brand;
    private BigDecimal price;
    
    @JsonProperty("was_price")
    private BigDecimal wasPrice;
    
    @JsonProperty("on_promotion")
    private Boolean onPromotion;
    
    @JsonProperty("last_seen_at")
    private String lastSeenAt;
    
    @JsonProperty("unit_price")
    private UnitPrice unitPrice;

    @Data
    public static class UnitPrice {
        private BigDecimal value;
        private String per;
    }
}