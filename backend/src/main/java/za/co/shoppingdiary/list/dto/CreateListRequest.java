package za.co.shoppingdiary.list.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class CreateListRequest {

    @NotBlank @Size(max = 200)
    private String name;

    @DecimalMin("0.00") @Digits(integer = 8, fraction = 2)
    private BigDecimal budgetAmount;

    @Size(max = 1000)
    @lombok.Getter @lombok.Setter
    private String description;

    @Size(max = 150)
    @lombok.Getter @lombok.Setter
    private String locationLabel;

    @lombok.Getter @lombok.Setter
    private Double locationLat;

    @lombok.Getter @lombok.Setter
    private Double locationLon;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal budgetAmount) { this.budgetAmount = budgetAmount; }
}