package za.co.shoppingdiary.list.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

public class AddItemRequest {

    private Long productId;

    @Digits(integer = 7, fraction = 3)
    @DecimalMin(value = "0.001")
    private BigDecimal quantity = BigDecimal.ONE;

    @Size(max = 10)
    private String unit;

    @Getter @Setter @Size(max = 500)
    private String customName;

    @Getter @Setter @Size(max = 100)
    private String preferredBrand;

    @Getter @Setter @Size(max = 1000)
    private String notes;

    @Getter @Setter
    private Boolean flexibleBrand;

    @Getter @Setter
    private Long substituteProductId;

    @Getter @Setter
    private Boolean purchased;


    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}