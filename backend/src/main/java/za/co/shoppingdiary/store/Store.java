package za.co.shoppingdiary.store;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.time.Instant;

@Entity
@Table(name = "stores")
@Getter
@Setter
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "retailer_id", nullable = false)
    private Long retailerId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "store_code", length = 50)
    private String storeCode;

    @Column(length = 500)
    private String address;

    @Column(columnDefinition = "geometry(Point, 4326)", nullable = false)
    private Point location;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String province;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(name = "store_type", nullable = false, length = 30)
    private String storeType = "SUPERMARKET";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}