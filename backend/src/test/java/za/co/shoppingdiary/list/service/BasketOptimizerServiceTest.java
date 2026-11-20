package za.co.shoppingdiary.list.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BasketOptimizerServiceTest {
    @Test
    void preservesFractionalRemainderAfterApplyingBundle() {
        var price = new BasketOptimizerService.PriceData(
                new BigDecimal("30.00"), new BigDecimal("28.00"), new BigDecimal("50.00"), 2);

        assertEquals(new BigDecimal("64.00"), BasketOptimizerService.lineCost(price, new BigDecimal("2.5")));
    }

    @Test
    void preservesExactBundleTotalAcrossThreeItems() {
        var price = new BasketOptimizerService.PriceData(
                new BigDecimal("40.00"), null, new BigDecimal("100.00"), 3);

        assertEquals(new BigDecimal("100.00"), BasketOptimizerService.lineCost(price, new BigDecimal("3")));
    }

    @Test
    void keepsRegularDiscountWhenBundlesCostMore() {
        var price = new BasketOptimizerService.PriceData(
                new BigDecimal("30.00"), new BigDecimal("20.00"), new BigDecimal("50.00"), 2);

        assertEquals(new BigDecimal("40.00"), BasketOptimizerService.lineCost(price, new BigDecimal("2")));
    }
}
