package za.co.shoppingdiary.retailer.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/directory")
@RequiredArgsConstructor
public class RetailDirectoryAdminController {
    private final JdbcClient jdbc;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxAgeDays;

    @GetMapping
    public List<Coverage> directory() {
        return jdbc.sql("""
            SELECT r.id, r.name, r.slug, r.website, r.logo_url, r.active,
                   count(s.id) FILTER (WHERE s.active AND s.store_code IS DISTINCT FROM 'ONLINE') AS branches,
                   count(s.id) FILTER (WHERE s.active AND r.active AND s.store_code IS DISTINCT FROM 'ONLINE'
                       AND coverage.usable) AS priced_branches,
                   max(coverage.latest) FILTER (WHERE s.store_code IS DISTINCT FROM 'ONLINE') AS last_observed,
                   max(coverage.collected) FILTER (WHERE s.store_code IS DISTINCT FROM 'ONLINE') AS last_collected
            FROM retailers r
            LEFT JOIN stores s ON s.retailer_id = r.id
            LEFT JOIN LATERAL (
                SELECT max(po.observed_at) AS latest, max(po.created_at) AS collected,
                       EXISTS (
                           SELECT 1 FROM (
                               SELECT DISTINCT product_id FROM price_observations
                               WHERE store_id = s.id
                                 AND observed_at >= CURRENT_TIMESTAMP - make_interval(days => :days)
                           ) recent
                           CROSS JOIN LATERAL current_branch_price(recent.product_id, s.id, :days)
                       ) AS usable
                FROM price_observations po WHERE po.store_id = s.id AND po.observed_at <= CURRENT_TIMESTAMP
            ) coverage ON s.id IS NOT NULL
            GROUP BY r.id ORDER BY r.name
            """).param("days", maxAgeDays).query(Coverage.class).list();
    }

    @GetMapping("/{retailerId}/branches")
    public List<Branch> branches(@PathVariable long retailerId) {
        return jdbc.sql("""
            SELECT s.id, s.name, s.store_code AS code, s.address, s.city, s.province, s.active, s.phone, s.hours_text, s.source_url, s.postal_code,
                   ST_Y(s.location) AS lat, ST_X(s.location) AS lon,
                   (SELECT max(observed_at) FROM price_observations WHERE store_id = s.id
                     AND observed_at <= CURRENT_TIMESTAMP) AS last_observed,
                   EXISTS (
                     SELECT 1 FROM (
                       SELECT DISTINCT product_id FROM price_observations
                       WHERE store_id = s.id AND observed_at >= CURRENT_TIMESTAMP - make_interval(days => :days)
                     ) recent CROSS JOIN LATERAL current_branch_price(recent.product_id, s.id, :days)
                     WHERE s.active AND s.store_code IS DISTINCT FROM 'ONLINE'
                   ) AS usable
            FROM stores s WHERE retailer_id = :retailerId ORDER BY s.name, s.id
            """).param("retailerId", retailerId).param("days", maxAgeDays).query(Branch.class).list();
    }

    @PutMapping("/retailers/{slug}")
    public long saveRetailer(@PathVariable @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,49}") String slug,
                             @Valid @RequestBody RetailerInput input) {
        if (!slug.matches("[a-z0-9][a-z0-9-]{0,49}")) throw new IllegalArgumentException("Invalid retailer slug");
        return jdbc.sql("""
            INSERT INTO retailers (name, slug, website, logo_url, active)
            VALUES (:name, :slug, :website, :logoUrl, :active)
            ON CONFLICT (slug) DO UPDATE SET name = excluded.name, website = excluded.website,
                logo_url = excluded.logo_url, active = excluded.active
            RETURNING id
            """).param("name", input.name()).param("website", input.website()).param("logoUrl", input.logoUrl())
                .param("active", input.active()).param("slug", slug).query(Long.class).single();
    }

    @PutMapping("/{retailerId}/branches/{code}")
    @Transactional
    public long saveBranch(@PathVariable long retailerId, @PathVariable String code,
                           @RequestParam(required = false) Long branchId, @Valid @RequestBody BranchInput input) {
        if (!code.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,49}") || code.equalsIgnoreCase("ONLINE")) {
            throw new IllegalArgumentException("Provide a physical branch code");
        }
        jdbc.sql("SELECT id FROM retailers WHERE id = :id FOR UPDATE").param("id", retailerId)
                .query(Long.class).optional().orElseThrow(() -> new IllegalArgumentException("Unknown retailer"));
        var parameters = new MapSqlParameterSource("retailer", retailerId).addValue("code", code)
                .addValue("name", input.name()).addValue("address", input.address())
                .addValue("city", input.city()).addValue("province", input.province())
                .addValue("lat", input.lat()).addValue("lon", input.lon()).addValue("active", input.active())
                .addValue("branchId", branchId, java.sql.Types.BIGINT)
                .addValue("phone", input.phone()).addValue("hours", input.hoursText())
                .addValue("source", input.sourceUrl()).addValue("postal", input.postalCode());
        boolean duplicate = jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM stores
              WHERE retailer_id = :retailer
                AND ((CAST(:branchId AS bigint) IS NULL AND store_code IS DISTINCT FROM :code)
                  OR (:branchId IS NOT NULL AND id <> :branchId))
                AND (lower(btrim(name)) = lower(btrim(:name)) OR
                     lower(btrim(address)) = lower(btrim(:address)))
                AND ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, 100))
            """).paramSource(parameters).query(Boolean.class).single();
        if (duplicate) throw new IllegalArgumentException("A nearby branch has the same name or address. Review its existing branch code.");
        if (branchId != null) {
            return jdbc.sql("""
                UPDATE stores SET store_code = :code, name = :name, address = :address,
                    city = :city, province = :province, location = ST_SetSRID(ST_MakePoint(:lon, :lat), 4326),
                    active = :active, phone = coalesce(:phone, phone), hours_text = coalesce(:hours, hours_text),
                    source_url = coalesce(:source, source_url), postal_code = coalesce(:postal, postal_code)
                WHERE id = :branchId AND retailer_id = :retailer AND store_code IS DISTINCT FROM 'ONLINE'
                RETURNING id
                """).paramSource(parameters).query(Long.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown physical branch"));
        }
        return jdbc.sql("""
            INSERT INTO stores (retailer_id, store_code, name, address, city, province, location, active, phone, hours_text, source_url, postal_code)
            VALUES (:retailer, :code, :name, :address, :city, :province,
                ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), :active, :phone, :hours, :source, :postal)
            ON CONFLICT (retailer_id, store_code) DO UPDATE SET name = excluded.name, address = excluded.address,
                city = excluded.city, province = excluded.province, location = excluded.location, active = excluded.active,
                phone = coalesce(excluded.phone, stores.phone), hours_text = coalesce(excluded.hours_text, stores.hours_text),
                source_url = coalesce(excluded.source_url, stores.source_url),
                postal_code = coalesce(excluded.postal_code, stores.postal_code)
            RETURNING id
            """).paramSource(parameters).query(Long.class).single();
    }

    @PutMapping("/{retailerId}/aliases")
    public void saveProductAlias(@PathVariable long retailerId, @Valid @RequestBody AliasInput input) {
        int saved = jdbc.sql("""
            INSERT INTO product_aliases (product_id, retailer_id, raw_name, confidence, matched_by)
            SELECT p.id, r.id, :rawName, 1, 'MANUAL'
            FROM products p CROSS JOIN retailers r
            WHERE p.barcode = :barcode AND r.id = :retailerId
            ON CONFLICT (retailer_id, raw_name) DO UPDATE SET product_id = excluded.product_id,
                confidence = excluded.confidence, matched_by = excluded.matched_by
            """).param("rawName", input.rawName()).param("barcode", input.barcode()).param("retailerId", retailerId).update();
        if (saved == 0) throw new IllegalArgumentException("Unknown retailer or catalogue barcode");
    }

    public record AliasInput(@NotBlank @Size(max = 500) String rawName,
                             @NotBlank @Pattern(regexp = "[0-9]{8,20}") String barcode) {}
    public record Coverage(long id, String name, String slug, String website, String logoUrl, boolean active,
                           long branches, long pricedBranches, OffsetDateTime lastObserved, OffsetDateTime lastCollected) {}
    public record Branch(long id, String name, String code, String address, String city, String province, boolean active,
                         double lat, double lon, OffsetDateTime lastObserved, boolean usable,
                         String phone, String hoursText, String sourceUrl, String postalCode) {}
    public record RetailerInput(@NotBlank @Size(max = 100) String name,
            @Size(max = 255) @Pattern(regexp = "https://\\S+") String website,
            @Size(max = 500) @Pattern(regexp = "https://\\S+") String logoUrl, boolean active) {}
    public record BranchInput(@NotBlank @Size(max = 255) String name, @NotBlank @Size(max = 500) String address,
            @NotBlank @Size(max = 100) String city, @NotBlank @Size(max = 100) String province,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lon, boolean active,
            @Size(max = 50) @Pattern(regexp = "[+0-9 ()-]*") String phone,
            @Size(max = 500) String hoursText,
            @Size(max = 500) @Pattern(regexp = "https://\\S+|") String sourceUrl,
            @Size(max = 10) String postalCode) {}
}
