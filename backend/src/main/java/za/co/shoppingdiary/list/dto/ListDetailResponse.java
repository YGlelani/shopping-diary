package za.co.shoppingdiary.list.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class ListDetailResponse {

    @lombok.Getter @lombok.Setter private String description;
    @lombok.Getter @lombok.Setter private Instant updatedAt;
    @lombok.Getter @lombok.Setter private long revision;
    @lombok.Getter @lombok.Setter private String locationLabel;
    @lombok.Getter @lombok.Setter private Double locationLat;
    @lombok.Getter @lombok.Setter private Double locationLon;
    private Long id;
    private String name;
    private BigDecimal budgetAmount;
    private String status;
    private Instant createdAt;
    private List<Item> items;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal budgetAmount) { this.budgetAmount = budgetAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public static class Item {
        @lombok.Getter @lombok.Setter private String customName;
        @lombok.Getter @lombok.Setter private String preferredBrand;
        @lombok.Getter @lombok.Setter private String category;
        @lombok.Getter @lombok.Setter private String barcode;
        @lombok.Getter @lombok.Setter private String imageUrl;
        @lombok.Getter @lombok.Setter private String notes;
        @lombok.Getter @lombok.Setter private boolean flexibleBrand;
        @lombok.Getter @lombok.Setter private boolean purchased;
        @lombok.Getter @lombok.Setter private Long substituteProductId;
        @lombok.Getter @lombok.Setter private BigDecimal combinedMeasure;
        @lombok.Getter @lombok.Setter private String measureUnit;
        private Long id;
        private Long productId;
        private String productName;
        private String brand;
        private String sizeText;
        private BigDecimal quantity;
        private String unit;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }

        public String getBrand() { return brand; }
        public void setBrand(String brand) { this.brand = brand; }

        public String getSizeText() { return sizeText; }
        public void setSizeText(String sizeText) { this.sizeText = sizeText; }

        public BigDecimal getQuantity() { return quantity; }
        public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
    }
}