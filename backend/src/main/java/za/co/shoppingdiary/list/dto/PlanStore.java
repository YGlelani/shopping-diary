package za.co.shoppingdiary.list.dto;

import java.math.BigDecimal;

public class PlanStore {
    @lombok.Getter @lombok.Setter private String address;


    private Long storeId;
    private String storeName;
    private String retailerName;
    private double distanceKm;
    private BigDecimal transportCost;
    private BigDecimal storeSubtotal;

    public PlanStore() {}
    public PlanStore(Long storeId, String storeName, String retailerName,
                     double distanceKm, BigDecimal transportCost, BigDecimal storeSubtotal) {
        this.storeId = storeId;
        this.storeName = storeName;
        this.retailerName = retailerName;
        this.distanceKm = distanceKm;
        this.transportCost = transportCost;
        this.storeSubtotal = storeSubtotal;
    }

    public Long getStoreId() { return storeId; }
    public String getStoreName() { return storeName; }
    public String getRetailerName() { return retailerName; }
    public double getDistanceKm() { return distanceKm; }
    public BigDecimal getTransportCost() { return transportCost; }
    public BigDecimal getStoreSubtotal() { return storeSubtotal; }
}