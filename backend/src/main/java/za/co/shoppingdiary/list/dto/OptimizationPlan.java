package za.co.shoppingdiary.list.dto;

import java.math.BigDecimal;
import java.util.List;

public class OptimizationPlan {
    @lombok.Getter @lombok.Setter private String strategy;
    @lombok.Getter @lombok.Setter private String budgetStatus;
    @lombok.Getter @lombok.Setter private BigDecimal budgetDifference;
    @lombok.Getter @lombok.Setter private BigDecimal knownCost;
    @lombok.Getter @lombok.Setter private Double roundTripKm;
    @lombok.Getter @lombok.Setter private List<MissingItem> unresolved;
    public record MissingItem(long itemId, String name, BigDecimal quantity, String unit, String reason) {}


    @lombok.Getter @lombok.Setter private String fingerprint;
    private List<PlanStore> stores;
    private List<PlanItem> items;
    private BigDecimal totalProductCost;
    private BigDecimal totalTransportCost;
    private BigDecimal grandTotal;
    private int missingItems;

    public OptimizationPlan() {}
    public OptimizationPlan(List<PlanStore> stores, List<PlanItem> items,
                            BigDecimal totalProductCost, BigDecimal totalTransportCost,
                            BigDecimal grandTotal, int missingItems) {
        this.stores = stores;
        this.items = items;
        this.totalProductCost = totalProductCost;
        this.totalTransportCost = totalTransportCost;
        this.grandTotal = grandTotal;
        this.missingItems = missingItems;
    }

    public List<PlanStore> getStores() { return stores; }
    public List<PlanItem> getItems() { return items; }
    public BigDecimal getTotalProductCost() { return totalProductCost; }
    public BigDecimal getTotalTransportCost() { return totalTransportCost; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public int getMissingItems() { return missingItems; }
}