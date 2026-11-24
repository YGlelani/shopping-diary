package za.co.shoppingdiary.list.dto;

import java.math.BigDecimal;
import java.util.List;

public class OptimizeResponse {
    @lombok.Getter @lombok.Setter private OptimizationPlan bestSplit;
    @lombok.Getter @lombok.Setter private OptimizationPlan bestAlternatives;
    @lombok.Getter @lombok.Setter private BigDecimal budgetAmount;
    @lombok.Getter @lombok.Setter private boolean searchLimited;
    @lombok.Getter @lombok.Setter private int branchesSearched;
    @lombok.Getter @lombok.Setter private long listRevision;
    @lombok.Getter @lombok.Setter private java.time.OffsetDateTime calculatedAt;
    @lombok.Getter @lombok.Setter private List<PlanStore> branches;
    @lombok.Getter @lombok.Setter private OptimizeRequest assumptions;


    private OptimizationPlan recommendation;
    private OptimizationPlan bestSingleStore;
    private OptimizationPlan bestTwoStore;
    private OptimizationPlan bestThreeStore;
    private BigDecimal savingsVsBestSingle;
    private BigDecimal savingsPct;
    private double costPerKm;
    private double radiusKm;
    private int totalItems;
    private int skippedItems;

    public OptimizeResponse() {}
    public OptimizeResponse(OptimizationPlan recommendation, OptimizationPlan bestSingleStore,
                            OptimizationPlan bestTwoStore, OptimizationPlan bestThreeStore,
                            BigDecimal savingsVsBestSingle, BigDecimal savingsPct,
                            double costPerKm, double radiusKm, int totalItems, int skippedItems) {
        this.recommendation = recommendation;
        this.bestSingleStore = bestSingleStore;
        this.bestTwoStore = bestTwoStore;
        this.bestThreeStore = bestThreeStore;
        this.savingsVsBestSingle = savingsVsBestSingle;
        this.savingsPct = savingsPct;
        this.costPerKm = costPerKm;
        this.radiusKm = radiusKm;
        this.totalItems = totalItems;
        this.skippedItems = skippedItems;
    }

    public OptimizationPlan getRecommendation() { return recommendation; }
    public OptimizationPlan getBestSingleStore() { return bestSingleStore; }
    public OptimizationPlan getBestTwoStore() { return bestTwoStore; }
    public OptimizationPlan getBestThreeStore() { return bestThreeStore; }
    public BigDecimal getSavingsVsBestSingle() { return savingsVsBestSingle; }
    public BigDecimal getSavingsPct() { return savingsPct; }
    public double getCostPerKm() { return costPerKm; }
    public double getRadiusKm() { return radiusKm; }
    public int getTotalItems() { return totalItems; }
    public int getSkippedItems() { return skippedItems; }
}