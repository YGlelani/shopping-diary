package za.co.shoppingdiary.ingestion.api.dto;

import lombok.Data;
import java.util.List;

@Data
public class ReefApiResponse {
    private List<ReefApiProduct> data;
}