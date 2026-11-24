package za.co.shoppingdiary.budget;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetAssessmentTest {
    @ParameterizedTest
    @CsvSource({
        "500.00, 0, 0, 0, false, EMPTY, , , false",
        "500.00, 0, 3, 0, false, INSUFFICIENT_PRICES, , , false",
        "500.00, 320.19, 8, 6, true, INCOMPLETE, 179.81, , false",
        "500.00, 500.01, 8, 6, true, OVER_BUDGET, -0.01, 0.01, false",
        "1500.00, 1349.99, 4, 4, true, WITHIN_BUDGET, 150.01, , false",
        "1500.00, 1350.00, 4, 4, true, WITHIN_BUDGET, 150.00, , true",
        "500.00, 500.00, 1, 1, true, WITHIN_BUDGET, 0.00, , true",
        "500.00, 320.19, 2, 2, false, MULTI_STORE_ESTIMATE, 179.81, , false"
    })
    void assessesOnlyKnownPrices(String budget, String subtotal, int entries, int priced, boolean singleBranch,
                                String status, String remaining, String overBy, boolean approaching) {
        var result = BudgetAssessment.calculate(decimal(budget), decimal(subtotal), entries, priced, singleBranch);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.knownSubtotal()).isEqualTo(priced == 0 ? null : decimal(subtotal));
        assertThat(result.remainingAfterKnown()).isEqualTo(decimal(remaining));
        assertThat(result.overBy()).isEqualTo(decimal(overBy));
        assertThat(result.unpriced()).isEqualTo(entries - priced);
        assertThat(result.approaching()).isEqualTo(approaching);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
