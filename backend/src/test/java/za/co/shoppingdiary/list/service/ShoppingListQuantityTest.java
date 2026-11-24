package za.co.shoppingdiary.list.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShoppingListQuantityTest {
    @Test
    void allowsMeasuredFractionsAndWholePackageCounts() {
        assertEquals(new BigDecimal("1.125"), ShoppingListService.quantity(new BigDecimal("1.125"), "kg"));
        assertEquals(new BigDecimal("3"), ShoppingListService.quantity(new BigDecimal("3"), "pack"));
        assertEquals(new BigDecimal("2"), ShoppingListService.quantity(new BigDecimal("2"), "dozen"));
        assertThrows(IllegalArgumentException.class, () -> ShoppingListService.quantity(new BigDecimal("1.5"), "pack"));
        assertThrows(IllegalArgumentException.class, () -> ShoppingListService.quantity(new BigDecimal("1.0005"), "kg"));
        assertThrows(IllegalArgumentException.class, () -> ShoppingListService.quantity(BigDecimal.ZERO, "item"));
        assertThrows(IllegalArgumentException.class, () -> ShoppingListService.quantity(new BigDecimal("10000000"), "item"));
    }
}
