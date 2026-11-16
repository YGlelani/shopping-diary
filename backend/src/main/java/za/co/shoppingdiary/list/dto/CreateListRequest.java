package za.co.shoppingdiary.list.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class CreateListRequest {

    @NotBlank @Size(max = 200)
    private String name;

    private BigDecimal budgetAmount;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal budgetAmount) { this.budgetAmount = budgetAmount; }
}