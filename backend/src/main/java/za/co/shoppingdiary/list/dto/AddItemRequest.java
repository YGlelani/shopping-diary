package za.co.shoppingdiary.list.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class AddItemRequest {

    @NotNull
    private Long productId;

    @DecimalMin(value = "0.001")
    private BigDecimal quantity = BigDecimal.ONE;

    private String unit;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}