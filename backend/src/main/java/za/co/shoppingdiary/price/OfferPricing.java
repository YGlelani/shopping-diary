package za.co.shoppingdiary.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

public final class OfferPricing {
    private static final Pattern PACKAGE = Pattern.compile(
            "(?i)^\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|g|ml|l|count|pack|pk)\\s*$");

    private OfferPricing() {}

    public record Measure(BigDecimal quantity, String unit) {}

    public static Measure measure(BigDecimal quantity, String unit, String sizeText) {
        if (quantity == null || quantity.signum() <= 0 || unit == null) {
            if (sizeText == null) return null;
            var match = PACKAGE.matcher(sizeText);
            if (!match.matches()) return null;
            quantity = new BigDecimal(match.group(1).replace(',', '.'));
            unit = match.group(2).toLowerCase(java.util.Locale.ROOT);
        }
        if (quantity.signum() <= 0) return null;
        return switch (unit) {
            case "kg", "l" -> new Measure(quantity, unit.equals("kg") ? "kg" : "L");
            case "g", "ml" -> new Measure(quantity.movePointLeft(3), unit.equals("g") ? "kg" : "L");
            case "count", "pack", "pk" -> new Measure(quantity, "item");
            default -> null;
        };
    }

    public static BigDecimal total(BigDecimal packagePrice, BigDecimal quantity,
                                   BigDecimal bundlePrice, Integer bundleSize) {
        if (packagePrice == null || packagePrice.signum() <= 0 || quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Invalid price or quantity");
        }
        BigDecimal total = packagePrice.multiply(quantity);
        if (bundlePrice != null && bundlePrice.signum() > 0 && bundleSize != null && bundleSize > 1) {
            BigDecimal[] bundles = quantity.divideAndRemainder(BigDecimal.valueOf(bundleSize));
            total = total.min(bundlePrice.multiply(bundles[0]).add(packagePrice.multiply(bundles[1])));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
