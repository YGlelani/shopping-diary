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
    
    @JsonProperty("image_url")
    private String imageUrl;
    
    private BigDecimal price;
    private String currency;
    
    @JsonProperty("unit_size")
    private String unitSize;
    
    @JsonProperty("in_stock")
    private Boolean inStock;
    
    private String category;
    
    @JsonProperty("last_seen_at")
    private String lastSeenAt;
    
    @JsonProperty("was_price")
    private BigDecimal wasPrice;
    
    @JsonProperty("on_promotion")
    private Boolean onPromotion;
    
    private String promotion;
    
    @JsonProperty("loyalty_price")
    private BigDecimal loyaltyPrice;
    
    @JsonProperty("unit_price")
    private UnitPrice unitPrice;

    @Data
    public static class UnitPrice {
        private BigDecimal value;
        private String per;
    }
}