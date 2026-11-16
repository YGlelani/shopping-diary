package za.co.shoppingdiary.list.dto;

import java.math.BigDecimal;

public class BasketRow {

    private Long storeId;
    private String storeName;
    private String retailerName;
    private double distanceMeters;
    private BigDecimal subtotal;
    private int coveredItems;
    private int totalItems;
    private int missingItems;
    private double coveragePct;

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }

    public String getRetailerName() { return retailerName; }
    public void setRetailerName(String retailerName) { this.retailerName = retailerName; }

    public double getDistanceMeters() { return distanceMeters; }
    public void setDistanceMeters(double distanceMeters) { this.distanceMeters = distanceMeters; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public int getCoveredItems() { return coveredItems; }
    public void setCoveredItems(int coveredItems) { this.coveredItems = coveredItems; }

    public int getTotalItems() { return totalItems; }
    public void setTotalItems(int totalItems) { this.totalItems = totalItems; }

    public int getMissingItems() { return missingItems; }
    public void setMissingItems(int missingItems) { this.missingItems = missingItems; }

    public double getCoveragePct() { return coveragePct; }
    public void setCoveragePct(double coveragePct) { this.coveragePct = coveragePct; }
}