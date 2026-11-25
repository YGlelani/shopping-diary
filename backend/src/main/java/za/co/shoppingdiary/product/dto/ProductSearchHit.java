package za.co.shoppingdiary.product.dto;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

public class ProductSearchHit {

    private Long productId;
    private String canonicalName;
    private String brand;
    private String barcode;
    private String sizeText;
    private BigDecimal minEffectivePrice;
    @Getter @Setter private BigDecimal catalogueAmount;
    @Getter @Setter private String catalogueRetailer;
    @Getter @Setter private java.time.OffsetDateTime catalogueObservedAt;
    private int storeCount;
    private Instant latestObservedAt;
    private String imageUrl;
    private String category;
    @Getter @Setter private String sourceUrl;
    @Getter @Setter private String imageSourceUrl;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getSizeText() {
        return sizeText;
    }

    public void setSizeText(String sizeText) {
        this.sizeText = sizeText;
    }

    public BigDecimal getMinEffectivePrice() {
        return minEffectivePrice;
    }

    public void setMinEffectivePrice(BigDecimal minEffectivePrice) {
        this.minEffectivePrice = minEffectivePrice;
    }

    public int getStoreCount() {
        return storeCount;
    }

    public void setStoreCount(int storeCount) {
        this.storeCount = storeCount;
    }

    public Instant getLatestObservedAt() {
        return latestObservedAt;
    }

    public void setLatestObservedAt(Instant latestObservedAt) {
        this.latestObservedAt = latestObservedAt;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}