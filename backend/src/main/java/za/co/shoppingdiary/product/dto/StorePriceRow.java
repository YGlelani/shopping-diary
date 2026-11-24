package za.co.shoppingdiary.product.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class StorePriceRow {

    private Long storeId;
    private String storeName;
    private String retailerName;
    private double distanceMeters;
    private BigDecimal shelfPrice;
    private BigDecimal promoPrice;
    private BigDecimal memberPrice;
    private BigDecimal unitPrice;
    private Instant observedAt;
    private String verificationStatus;
    private String stockStatus;
    private final List<Promotion> promotions = new ArrayList<>();

    public record Promotion(String type, String description, BigDecimal price, Integer minQuantity, LocalDate endsOn) {}

    public List<Promotion> getPromotions() { return promotions; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }

    public String getRetailerName() { return retailerName; }
    public void setRetailerName(String retailerName) { this.retailerName = retailerName; }

    public double getDistanceMeters() { return distanceMeters; }
    public void setDistanceMeters(double distanceMeters) { this.distanceMeters = distanceMeters; }

    public BigDecimal getShelfPrice() { return shelfPrice; }
    public void setShelfPrice(BigDecimal shelfPrice) { this.shelfPrice = shelfPrice; }

    public BigDecimal getPromoPrice() { return promoPrice; }
    public void setPromoPrice(BigDecimal promoPrice) { this.promoPrice = promoPrice; }

    public BigDecimal getMemberPrice() { return memberPrice; }
    public void setMemberPrice(BigDecimal memberPrice) { this.memberPrice = memberPrice; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }

    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }

    public String getStockStatus() { return stockStatus; }
    public void setStockStatus(String stockStatus) { this.stockStatus = stockStatus; }
}