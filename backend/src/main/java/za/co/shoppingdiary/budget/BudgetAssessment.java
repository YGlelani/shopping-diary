package za.co.shoppingdiary.budget;

import java.math.BigDecimal;

public record BudgetAssessment(String status, BigDecimal knownSubtotal, BigDecimal remainingAfterKnown,
                               BigDecimal overBy, int entries, int priced, int unpriced, boolean approaching) {
    public static BudgetAssessment calculate(BigDecimal funds, BigDecimal subtotal, int entries, int priced,
                                              boolean singleBranch) {
        if (entries == 0) return new BudgetAssessment("EMPTY", null, null, null, 0, 0, 0, false);
        if (priced == 0) return new BudgetAssessment("INSUFFICIENT_PRICES", null, null, null, entries, 0, entries, false);
        BigDecimal difference = funds.subtract(subtotal);
        String status = difference.signum() < 0 ? "OVER_BUDGET" : priced < entries ? "INCOMPLETE"
                : singleBranch ? "WITHIN_BUDGET" : "MULTI_STORE_ESTIMATE";
        return new BudgetAssessment(status, subtotal, difference, difference.signum() < 0 ? difference.negate() : null,
                entries, priced, entries - priced, difference.signum() >= 0 && funds.signum() > 0
                && subtotal.compareTo(funds.multiply(new BigDecimal("0.90"))) >= 0);
    }
}
