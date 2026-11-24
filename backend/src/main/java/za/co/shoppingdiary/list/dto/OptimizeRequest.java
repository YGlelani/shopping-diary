package za.co.shoppingdiary.list.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Set;

@Getter
@Setter
public class OptimizeRequest {
    @NotNull @DecimalMin("-90") @DecimalMax("90")
    private Double lat;
    @NotNull @DecimalMin("-180") @DecimalMax("180")
    private Double lon;
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100")
    private Double radiusKm = 15.0;
    @DecimalMin("0") @DecimalMax("1000") @Digits(integer=4, fraction=2)
    private BigDecimal costPerKm;
    @DecimalMin("0") @Digits(integer=8, fraction=2)
    private BigDecimal tripCost;
    @DecimalMin("0") @Digits(integer=8, fraction=2)
    private BigDecimal budgetAmount;
    @Positive private Long budgetId;
    @Min(1) @Max(3) private int maxStores = 2;
    @NotNull private TravelMode travelMode = TravelMode.UNKNOWN;
    private boolean allowAlternatives = true;
    private boolean ignorePromotions;
    @NotNull @Size(max=250) private Set<@NotNull @Positive Long> confirmedLoyaltyRetailers = Set.of();
    @NotNull @Size(max=250) private Set<@NotNull @Positive Long> excludedRetailers = Set.of();
    @Positive private Long preferredRetailer;
    @NotNull @Size(max=1000) private Set<@NotNull @Positive Long> rejectedAlternatives = Set.of();

    public enum TravelMode { UNKNOWN, WALK, DRIVE, PUBLIC_TRANSPORT, FIXED }
}
