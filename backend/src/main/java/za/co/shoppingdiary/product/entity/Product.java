package za.co.shoppingdiary.product.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
@Getter
@Setter
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "canonical_name", nullable = false, length = 255)
    private String canonicalName;

    @Column(length = 100)
    private String brand;

    @Column(length = 20, unique = true)
    private String barcode;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "base_unit", length = 10)
    private String baseUnit;

    @Column(name = "base_quantity", precision = 12, scale = 4)
    private BigDecimal baseQuantity;

    @Column(name = "size_text", length = 50)
    private String sizeText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(length = 100)
    private String category;

    @Column(name = "catalogue_source_url", length = 1000)
    private String catalogueSourceUrl;

    @Column(name = "image_source_url", length = 1000)
    private String imageSourceUrl;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}