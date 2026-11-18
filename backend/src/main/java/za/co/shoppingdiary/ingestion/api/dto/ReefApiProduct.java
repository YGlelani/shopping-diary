package za.co.shoppingdiary.ingestion.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class ReefApiProduct {
    private String barcode;
    private String name;
    
    @JsonProperty("store_id") // Or whatever ReefAPI calls the store identifier
    private String storeId;
    
    @JsonProperty("promo_price")
    private BigDecimal promoPrice;
    
    @JsonProperty("promo_description")
    private String promoDescription;
    
    @JsonProperty("min_quantity")
    private Integer minQuantity;
    
    // If ReefAPI provides dates, map them here
    @JsonProperty("promo_start_date")
    private String startDate;
    
    @JsonProperty("promo_end_date")
    private String endDate;
}