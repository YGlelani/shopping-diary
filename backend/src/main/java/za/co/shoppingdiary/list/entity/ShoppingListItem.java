package za.co.shoppingdiary.list.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "shopping_list_items")
@Getter
@Setter
public class ShoppingListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "list_id", nullable = false)
    private Long listId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "free_text", length = 500)
    private String freeText;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(length = 10)
    private String unit;

    @Column(name = "preferred_brand", length = 100)
    private String preferredBrand;

    @Column(name = "flexible_brand", nullable = false)
    private boolean flexibleBrand = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}