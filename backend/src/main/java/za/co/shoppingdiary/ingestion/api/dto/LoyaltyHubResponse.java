package za.co.shoppingdiary.ingestion.api.dto;

import lombok.Data;
import java.util.List;

@Data
public class LoyaltyHubResponse {
    private List<LoyaltyHubPrice> data;
    private Meta meta;

    @Data
    public static class Meta {
        private Integer count;
        private Integer total;
        private Boolean hasMore;
        private String updatedAt;
    }
}