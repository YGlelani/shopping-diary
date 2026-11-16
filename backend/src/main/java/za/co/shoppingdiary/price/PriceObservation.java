package za.co.shoppingdiary.price;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_observations")
@Getter
@Setter
public class PriceObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "scrape_job_id")
    private Long scrapeJobId;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_reference", length = 500)
    private String sourceReference;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "shelf_price", precision = 10, scale = 2)
    private BigDecimal shelfPrice;

    @Column(name = "promo_price", precision = 10, scale = 2)
    private BigDecimal promoPrice;

    @Column(name = "member_price", precision = 10, scale = 2)
    private BigDecimal memberPrice;

    @Column(name = "unit_price", precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "stock_status", nullable = false, length = 20)
    private String stockStatus = "UNKNOWN";

    @Column(name = "verification_status", nullable = false, length = 20)
    private String verificationStatus = "UNVERIFIED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "content_hash", nullable = false, unique = true, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}