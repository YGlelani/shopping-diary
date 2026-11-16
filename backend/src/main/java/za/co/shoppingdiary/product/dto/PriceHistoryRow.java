package za.co.shoppingdiary.product.dto;

import java.math.BigDecimal;
import java.time.Instant;

public class PriceHistoryRow {
    private String storeName;
    private BigDecimal price;
    private Instant observedAt;

    public PriceHistoryRow(String storeName, BigDecimal price, Instant observedAt) {
        this.storeName = storeName;
        this.price = price;
        this.observedAt = observedAt;
    }

    public String getStoreName() { return storeName; }
    public BigDecimal getPrice() { return price; }
    public Instant getObservedAt() { return observedAt; }
}