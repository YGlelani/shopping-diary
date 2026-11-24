package za.co.shoppingdiary.list.dto;

import java.math.BigDecimal;

public class PlanItem {
    @lombok.Getter @lombok.Setter private Long itemId;
    @lombok.Getter @lombok.Setter private Long requestedProductId;
    @lombok.Getter @lombok.Setter private String sizeText;
    @lombok.Getter @lombok.Setter private String imageUrl;
    @lombok.Getter @lombok.Setter private Long observationId;
    @lombok.Getter @lombok.Setter private java.time.OffsetDateTime observedAt;
    @lombok.Getter @lombok.Setter private String verificationStatus;
    @lombok.Getter @lombok.Setter private String sourceType;
    @lombok.Getter @lombok.Setter private java.time.LocalDate promotionEndsOn;
    @lombok.Getter @lombok.Setter private String priceType;
    @lombok.Getter @lombok.Setter private Long promotionId;
    @lombok.Getter @lombok.Setter private String promotionTerms;
    @lombok.Getter @lombok.Setter private BigDecimal shelfLineTotal;
    @lombok.Getter @lombok.Setter private BigDecimal exactLineTotal;

    @lombok.Getter @lombok.Setter private String substitutedFor;

    private Long productId;
    private String productName;
    private BigDecimal quantity;
    private Long assignedStoreId;
    private String assignedStoreName;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;

    public PlanItem() {}
    public PlanItem(Long productId, String productName, BigDecimal quantity,
                    Long assignedStoreId, String assignedStoreName,
                    BigDecimal unitPrice, BigDecimal lineTotal) {
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.assignedStoreId = assignedStoreId;
        this.assignedStoreName = assignedStoreName;
        this.unitPrice = unitPrice;
        this.lineTotal = lineTotal;
    }

    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getQuantity() { return quantity; }
    public Long getAssignedStoreId() { return assignedStoreId; }
    public String getAssignedStoreName() { return assignedStoreName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineTotal() { return lineTotal; }
}