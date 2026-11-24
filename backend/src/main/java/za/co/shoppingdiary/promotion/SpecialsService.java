package za.co.shoppingdiary.promotion;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SpecialsService {
    private final JdbcClient jdbc;
    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxAge;

    public enum Kind { DISCOUNT,PERCENT,MULTIBUY,BOGOF,MEMBER,COUPON,BUNDLE }
    public enum Scope { BRANCH,REGION,NATIONAL,ONLINE,UNKNOWN }
    public enum Verification { VERIFIED,ADVERTISED,UNVERIFIED }
    public enum Status { SCHEDULED,ACTIVE,EXPIRED,PENDING,REJECTED,SUSPENDED }
    public enum Sort { ENDING,PRICE,SAVING,DISCOUNT,NEAREST,NEWEST }

    @Getter @Setter
    public static class Filters {
        @Size(max=120) private String q;
        @Size(max=80) private String retailer;
        @Size(max=120) private String category;
        @Size(max=120) private String brand;
        @Positive private Long productId;
        @Positive private Long branchId;
        @DecimalMin("-90") @DecimalMax("90") private Double lat;
        @DecimalMin("-180") @DecimalMax("180") private Double lon;
        @DecimalMin(value="0",inclusive=false) @DecimalMax("100") private double radius=15;
        @DecimalMin("0") @Digits(integer=8,fraction=2) private BigDecimal minPrice;
        @DecimalMin("0") @Digits(integer=8,fraction=2) private BigDecimal maxPrice;
        @DecimalMin("1") @DecimalMax("1000000") @Digits(integer=7,fraction=0) private BigDecimal quantity=BigDecimal.ONE;
        @Min(0) @Max(10000) private int offset;
        @Min(1) @Max(51) private int limit=21;
        private LocalDate validOn;
        private Sort sort=Sort.ENDING;
        private Kind kind;
        private Verification verification;
        @Pattern(regexp="^(|PENDING|ACTIVE|UPCOMING|EXPIRED|UNVERIFIED|REJECTED|SUSPENDED)$") private String validity;
        private Boolean loyalty;
        private boolean memberConfirmed;
    }

    public record Deal(long id,long productId,String productName,String brand,String sizeText,String category,String imageUrl,
                       long retailerId,String retailer,String retailerSlug,String logoUrl,Long branchId,String branchName,long storeId,
                       Double distanceKm,String title,String description,Kind kind,Scope scope,String region,
                       BigDecimal offerAmount,Integer minimumQuantity,Integer freeQuantity,BigDecimal percentOff,
                       String loyaltyProgram,LocalDate startsOn,LocalDate endsOn,String validity,Verification verification,
                       String sourceType,String sourceReference,OffsetDateTime collectedAt,OffsetDateTime observedAt,
                       String stockStatus,BigDecimal quantity,BigDecimal shelfTotal,BigDecimal offeredTotal,BigDecimal saving,
                       BigDecimal savingPercent,String qualification,long revision,Long itemId) {}
    public record Coverage(long retailerId,String retailer,String retailerSlug,long currentOffers,long verifiedOffers,
                           OffsetDateTime latestCollectedAt) {}
    public record Audit(long id,String action,Long actorId,String beforeRecord,String afterRecord,OffsetDateTime happenedAt) {}

    public record Edit(@Positive long productId,@Positive long storeId,@NotNull Kind kind,@NotNull Scope scope,
                       @Size(max=100) String region,@NotBlank @Size(max=200) String title,
                       @NotBlank @Size(max=500) String description,@Size(max=120) String loyaltyProgram,
                       @DecimalMin(value="0",inclusive=false) @Digits(integer=8,fraction=2) BigDecimal price,
                       @Min(1) @Max(1000) Integer minimumQuantity,@Min(1) @Max(1000) Integer freeQuantity,
                       @DecimalMin(value="0",inclusive=false) @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal percentOff,
                       @NotNull LocalDate startsOn,@NotNull LocalDate endsOn,@NotBlank @Size(max=1000) String sourceReference,
                       @NotNull Verification verification) {}
    public record Review(@NotNull Status status,@PositiveOrZero long revision) {}

    private static final String TODAY = "(CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Johannesburg')::date";
    private static final String WINDOW = "p.status IN ('ACTIVE','SCHEDULED') AND " + TODAY + " BETWEEN p.start_date AND p.end_date";

    @Transactional(readOnly=true)
    public List<Deal> browse(Filters filters,Long deal,Long owner,Long list,boolean admin) {
        if ((filters.lat==null)!=(filters.lon==null) || !Double.isFinite(filters.radius)
                || (filters.lat!=null && (!Double.isFinite(filters.lat) || !Double.isFinite(filters.lon)))
                || (filters.minPrice!=null && filters.maxPrice!=null && filters.minPrice.compareTo(filters.maxPrice)>0)) {
            throw new IllegalArgumentException("Choose valid coordinates, radius and price bounds");
        }
        if (list!=null && !jdbc.sql("SELECT EXISTS(SELECT 1 FROM shopping_lists WHERE id=:list AND user_id=:owner)")
                .param("owner",owner).param("list",list).query(Boolean.class).single()) throw missing();
        String items = list==null ? "LEFT JOIN shopping_list_items item ON false" : """
            JOIN shopping_list_items item ON item.list_id=:list AND item.product_id=p0.id AND NOT item.purchased
            """;
        String visible = admin ? "TRUE" : deal==null && filters.validOn==null ? WINDOW
                : "p.status IN ('ACTIVE','SCHEDULED','EXPIRED')";
        String order = switch (filters.sort==null ? Sort.ENDING : filters.sort) {
            case PRICE -> "offered_total ASC NULLS LAST";
            case SAVING -> "saving DESC NULLS LAST";
            case DISCOUNT -> "saving_percent DESC NULLS LAST";
            case NEAREST -> "distance_km ASC NULLS LAST";
            case NEWEST -> "collected_at DESC";
            default -> "ends_on";
        };
        var sql = """
            WITH origin AS (SELECT ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography AS point)
            SELECT p.id,p0.id AS product_id,p0.canonical_name AS product_name,p0.brand,p0.size_text,
                   coalesce(c.name,p0.category) AS category,p0.image_url,
                   r.id AS retailer_id,r.name AS retailer,r.slug AS retailer_slug,r.logo_url,
                   b.branch_id,b.branch_name,p.store_id,b.distance_km,p.title,p.description,p.promo_type AS kind,p.scope,p.region,
                   p.promo_price AS offer_amount,p.min_quantity AS minimum_quantity,p.free_quantity,p.percent_off,
                   p.loyalty_program,p.start_date AS starts_on,p.end_date AS ends_on,
                   CASE WHEN p.status IN ('PENDING','REJECTED','SUSPENDED') THEN p.status
                        WHEN p.status='EXPIRED' OR p.end_date<%1$s THEN 'EXPIRED'
                        WHEN p.start_date>%1$s THEN 'UPCOMING'
                        WHEN p.verification='UNVERIFIED' THEN 'UNVERIFIED' ELSE 'ACTIVE' END AS validity,
                   p.verification,p.source_type,p.source_reference,p.collected_at,
                   obs.observed_at,latest.stock_status,coalesce(item.quantity,:quantity) AS quantity,
                   money.shelf_total,money.offered_total,
                   CASE WHEN obs.verification_status='VERIFIED' AND money.offered_total<money.shelf_total
                        THEN money.shelf_total-money.offered_total END AS saving,
                   CASE WHEN obs.verification_status='VERIFIED' AND money.offered_total<money.shelf_total
                        THEN round(100*(money.shelf_total-money.offered_total)/money.shelf_total,1) END AS saving_percent,
                   CASE WHEN NOT (%2$s) OR p.verification='UNVERIFIED' THEN 'NOT_CURRENT'
                        WHEN p.scope IN ('ONLINE','UNKNOWN') OR b.branch_id IS NULL THEN 'BRANCH_UNCONFIRMED'
                        WHEN p.promo_type IN ('COUPON','BUNDLE') THEN 'CHECK_TERMS'
                        WHEN NOT condition.brand_allowed THEN 'PREFERENCE_RESTRICTION'
                        WHEN (p.loyalty_program IS NOT NULL OR p.promo_type='MEMBER') AND NOT :member THEN 'LOYALTY_REQUIRED'
                        WHEN coalesce(item.quantity,:quantity)<coalesce(p.min_quantity,1)
                             + CASE WHEN p.promo_type='BOGOF' THEN coalesce(p.free_quantity,0) ELSE 0 END THEN 'QUANTITY_REQUIRED'
                        WHEN money.offered_total IS NULL THEN 'PRICE_EVIDENCE_REQUIRED'
                        WHEN money.shelf_total IS NOT NULL AND money.offered_total>=money.shelf_total THEN 'NO_SAVING'
                        ELSE 'QUALIFIES' END AS qualification,
                   p.revision,item.id AS item_id
            FROM promotions p JOIN products p0 ON p0.id=p.product_id
            LEFT JOIN categories c ON c.id=p0.category_id
            JOIN stores anchor ON anchor.id=p.store_id JOIN retailers r ON r.id=anchor.retailer_id
            CROSS JOIN origin
            %3$s
            LEFT JOIN LATERAL (
                SELECT s.id AS branch_id,s.name AS branch_name,ST_Distance(s.location::geography,origin.point)/1000 AS distance_km
                FROM promotion_branch_scope a JOIN stores s ON s.id=a.store_id
                WHERE a.promotion_id=p.id AND (:branch IS NULL OR s.id=:branch)
                  AND (origin.point IS NULL OR ST_DWithin(s.location::geography,origin.point,:radius))
                ORDER BY distance_km NULLS LAST,(s.id=anchor.id) DESC,s.id LIMIT 1
            ) b ON TRUE
            LEFT JOIN LATERAL eligible_branch_observation(p.product_id,b.branch_id,:age) obs ON TRUE
            LEFT JOIN LATERAL (
                SELECT stock_status FROM price_observations o WHERE o.product_id=p.product_id AND o.store_id=b.branch_id
                    AND o.observed_at<=CURRENT_TIMESTAMP ORDER BY o.observed_at DESC,o.id DESC LIMIT 1
            ) latest ON TRUE
            CROSS JOIN LATERAL (
                SELECT item.id IS NULL OR item.flexible_brand OR item.preferred_brand IS NULL
                    OR lower(p0.brand) IS NOT DISTINCT FROM lower(item.preferred_brand) AS brand_allowed
            ) condition
            CROSS JOIN LATERAL (
                SELECT CASE WHEN obs.shelf_price>0 THEN round(obs.shelf_price*coalesce(item.quantity,:quantity),2) END AS shelf_total,
                       CASE WHEN (%2$s) AND p.verification<>'UNVERIFIED' AND obs.id IS NOT NULL AND condition.brand_allowed
                         AND (p.promo_type<>'MEMBER' OR p.loyalty_program IS NOT NULL) THEN
                         promotion_quantity_total(p.promo_type,obs.shelf_price,coalesce(item.quantity,:quantity),p.promo_price,
                            p.min_quantity,p.percent_off,p.free_quantity,p.loyalty_program,:member)
                       END AS offered_total
            ) money
            WHERE (%4$s) AND (:id IS NULL OR p.id=:id)
              AND (:retailer IS NULL OR r.slug=:retailer)
              AND (:category IS NULL OR lower(coalesce(c.name,p0.category))=lower(:category))
              AND (:brand IS NULL OR p0.brand ILIKE :brand)
              AND (:product IS NULL OR p.product_id=:product)
              AND (:kind IS NULL OR p.promo_type=:kind)
              AND (:verification IS NULL OR p.verification=:verification)
              AND (:loyalty IS NULL OR :loyalty=(p.loyalty_program IS NOT NULL OR p.promo_type='MEMBER'))
              AND (:validOn IS NULL OR :validOn BETWEEN p.start_date AND p.end_date)
              AND (:minimum IS NULL OR p.promo_price>=:minimum)
              AND (:maximum IS NULL OR p.promo_price<=:maximum)
              AND (:q IS NULL OR NOT EXISTS (
                SELECT 1 FROM unnest(string_to_array(lower(:q),' ')) term
                WHERE strpos(lower(concat_ws(' ',p0.canonical_name,p0.brand,p0.size_text,p0.barcode,c.name,p0.category,r.name,p.title,p.description)),term)=0
              ))
              AND (:id IS NOT NULL OR (:branch IS NULL AND origin.point IS NULL) OR b.branch_id IS NOT NULL)
              AND (:admin OR r.active)
              AND (:validity IS NULL OR (CASE WHEN p.status IN ('PENDING','REJECTED','SUSPENDED') THEN p.status
                        WHEN p.status='EXPIRED' OR p.end_date<%1$s THEN 'EXPIRED'
                        WHEN p.start_date>%1$s THEN 'UPCOMING'
                        WHEN p.verification='UNVERIFIED' THEN 'UNVERIFIED' ELSE 'ACTIVE' END)=:validity)
            ORDER BY %5$s,p.id,item.id LIMIT :limit OFFSET :offset
            """.formatted(TODAY,WINDOW,items,visible,order);
        return jdbc.sql(sql).paramSource(new MapSqlParameterSource()
                .addValue("lat",filters.lat,Types.DOUBLE).addValue("lon",filters.lon,Types.DOUBLE)
                .addValue("radius",filters.radius*1000).addValue("branch",filters.branchId,Types.BIGINT)
                .addValue("id",deal,Types.BIGINT).addValue("list",list,Types.BIGINT).addValue("age",maxAge)
                .addValue("retailer",clean(filters.retailer),Types.VARCHAR).addValue("category",clean(filters.category),Types.VARCHAR)
                .addValue("brand",clean(filters.brand)==null ? null : "%"+filters.brand.strip()+"%",Types.VARCHAR)
                .addValue("product",filters.productId,Types.BIGINT).addValue("q",clean(filters.q),Types.VARCHAR)
                .addValue("validity",admin ? clean(filters.validity) : null,Types.VARCHAR)
                .addValue("kind",filters.kind==null ? null : filters.kind.name(),Types.VARCHAR)
                .addValue("verification",filters.verification==null ? null : filters.verification.name(),Types.VARCHAR)
                .addValue("loyalty",filters.loyalty,Types.BOOLEAN).addValue("validOn",filters.validOn,Types.DATE)
                .addValue("minimum",filters.minPrice,Types.NUMERIC).addValue("maximum",filters.maxPrice,Types.NUMERIC)
                .addValue("quantity",filters.quantity==null ? BigDecimal.ONE : filters.quantity)
                .addValue("member",filters.memberConfirmed).addValue("admin",admin)
                .addValue("limit",deal==null ? filters.limit : 1).addValue("offset",deal==null ? filters.offset : 0))
                .query(Deal.class).list();
    }

    public List<Coverage> coverage() {
        return jdbc.sql("""
            SELECT r.id AS retailer_id,r.name AS retailer,r.slug AS retailer_slug,
                   count(p.id) FILTER(WHERE %1$s AND p.verification<>'UNVERIFIED') AS current_offers,
                   count(p.id) FILTER(WHERE %1$s AND p.verification='VERIFIED') AS verified_offers,
                   max(p.collected_at) AS latest_collected_at
            FROM retailers r LEFT JOIN stores s ON s.retailer_id=r.id LEFT JOIN promotions p ON p.store_id=s.id
            WHERE r.active GROUP BY r.id ORDER BY current_offers DESC,r.name
            """.formatted(WINDOW)).query(Coverage.class).list();
    }

    @Transactional
    public long create(Edit input,long actor) {
        validate(input);
        auditActor(actor);
        return jdbc.sql("""
            INSERT INTO promotions(product_id,store_id,promo_type,scope,region,title,description,loyalty_program,
                promo_price,min_quantity,free_quantity,percent_off,start_date,end_date,source_reference,verification,source_type,status)
            VALUES (:product,:store,:kind,:scope,:region,:title,:description,:loyalty,:amount,:minimum,:free,:percent,
                :starts,:ends,:reference,:verification,'MANUAL','PENDING') RETURNING id
            """).paramSource(parameters(input)).query(Long.class).single();
    }

    @Transactional
    public void update(long id,long revision,Edit input,long actor) {
        validate(input);
        auditActor(actor);
        changed(jdbc.sql("""
            UPDATE promotions SET product_id=:product,store_id=:store,promo_type=:kind,scope=:scope,region=:region,
                title=:title,description=:description,loyalty_program=:loyalty,promo_price=:amount,min_quantity=:minimum,
                free_quantity=:free,percent_off=:percent,start_date=:starts,end_date=:ends,source_reference=:reference,
                verification=:verification,status='PENDING',revision=revision+1,updated_at=now()
            WHERE id=:id AND revision=:revision
            """).paramSource(parameters(input).addValue("id",id).addValue("revision",revision)).update());
    }

    @Transactional
    public void review(long id,Review review,long actor) {
        if (!List.of(Status.ACTIVE,Status.REJECTED,Status.SUSPENDED).contains(review.status())) {
            throw new IllegalArgumentException("Choose approve, reject or suspend");
        }
        auditActor(actor);
        changed(jdbc.sql("""
            UPDATE promotions SET status=:status,revision=revision+1,updated_at=now()
            WHERE id=:id AND revision=:revision
              AND (:status<>'ACTIVE' OR (end_date>=%1$s AND end_date>=start_date))
            """.formatted(TODAY)).param("id",id).param("revision",review.revision()).param("status",review.status().name()).update());
    }

    public List<Audit> audit(long id) {
        return jdbc.sql("""
            SELECT id,action,actor_id,before_record::text,after_record::text,happened_at
            FROM promotion_audit WHERE promotion_id=:id ORDER BY id DESC LIMIT 50
            """).param("id",id).query(Audit.class).list();
    }

    private void validate(Edit input) {
        if (input.endsOn().isBefore(input.startsOn()) || (input.scope()==Scope.REGION && clean(input.region())==null)
                || (input.kind()==Kind.MEMBER && clean(input.loyaltyProgram())==null)
                || (input.kind()==Kind.BOGOF && (input.minimumQuantity()==null || input.freeQuantity()==null))
                || (input.kind()==Kind.PERCENT && input.percentOff()==null)
                || (input.kind()==Kind.MULTIBUY && (input.minimumQuantity()==null || input.minimumQuantity()<2))
                || (List.of(Kind.DISCOUNT,Kind.MEMBER,Kind.MULTIBUY).contains(input.kind()) && input.price()==null)) {
            throw new IllegalArgumentException("Check the promotion dates, scope and required offer terms");
        }
        if (!jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM products WHERE id=:product) AND EXISTS(
                SELECT 1 FROM stores WHERE id=:store
                  AND (:scope<>'REGION' OR lower(province)=lower(:region))
                  AND (:scope<>'BRANCH' OR upper(store_code) IS DISTINCT FROM 'ONLINE'))
            """).param("product",input.productId()).param("store",input.storeId()).param("scope",input.scope().name())
                .param("region",clean(input.region()),Types.VARCHAR).query(Boolean.class).single()) {
            throw new IllegalArgumentException("Choose an existing catalogue product and a branch matching the offer scope");
        }
    }

    private void auditActor(long actor) {
        jdbc.sql("SELECT set_config('shopping_diary.promotion_actor',:actor,true)")
                .param("actor",Long.toString(actor)).query(String.class).single();
    }

    private static MapSqlParameterSource parameters(Edit in) {
        return new MapSqlParameterSource("product",in.productId()).addValue("store",in.storeId())
                .addValue("kind",in.kind().name()).addValue("scope",in.scope().name()).addValue("region",clean(in.region()))
                .addValue("title",in.title().strip()).addValue("description",in.description().strip())
                .addValue("loyalty",clean(in.loyaltyProgram())).addValue("amount",in.price()).addValue("minimum",in.minimumQuantity())
                .addValue("free",in.freeQuantity()).addValue("percent",in.percentOff()).addValue("starts",in.startsOn())
                .addValue("ends",in.endsOn()).addValue("reference",in.sourceReference().strip())
                .addValue("verification",in.verification().name());
    }

    private static String clean(String value) { return value==null || value.isBlank() ? null : value.strip(); }
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Special or shopping list not found"); }
    private static void changed(int count) {
        if (count==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"The promotion changed or is no longer eligible for approval");
    }
}
