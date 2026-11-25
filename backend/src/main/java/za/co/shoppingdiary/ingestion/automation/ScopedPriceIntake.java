package za.co.shoppingdiary.ingestion.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static za.co.shoppingdiary.ingestion.automation.SourceFields.*;

@Service
@RequiredArgsConstructor
public class ScopedPriceIntake {
    private final JdbcClient jdbc;
    private final BulkGroceryFiles files;
    private final LoyaltyCatalogue loyalty;

    public ImportCounts accept(String source,List<PriceDataSource.Price> rows,boolean dry) {
        if ("LOYALTYHUB".equals(source)) return loyalty.accept(rows,dry,null);
        var report=new ImportCounts();
        for (var row : rows) {
            report.examined++;
            var now=Instant.now();
            if (row.amount()==null || !"ZAR".equalsIgnoreCase(row.currency())
                    || (row.observedAt()!=null && row.observedAt().isAfter(now))
                    || (row.observedOn()!=null && row.observedOn().isAfter(java.time.LocalDate.now(java.time.ZoneId.of("Africa/Johannesburg"))))) {
                report.skip("Price, currency or observation date failed validation");
                continue;
            }
            String code=row.barcode()!=null && row.barcode().matches("[0-9]{8,14}") ? row.barcode() : null;
            String slug=retailerSlug(row.retailer());
            var retailer=slug==null ? null : jdbc.sql("SELECT id FROM retailers WHERE active AND slug=:slug")
                    .param("slug",slug).query(Long.class).optional().orElse(null);
            var product=code!=null ? jdbc.sql("SELECT id FROM products WHERE barcode=:barcode")
                    .param("barcode",code).query(Long.class).optional().orElse(null) : approvedAlias(jdbc,retailer,row);
            if (row.observedAt()==null && row.observedOn()==null) {
                files.review(source,key(row),"Missing provider observation date",row.name(),dry,report);
                continue;
            }
            String scope=row.scope()!=null && List.of("ONLINE","REGIONAL","CATALOGUE","BRANCH","UNKNOWN").contains(row.scope())
                    ? row.scope() : "UNKNOWN";
            var branch="BRANCH".equals(scope) && row.osmType()!=null && row.osmId()!=null
                    ? jdbc.sql("SELECT id FROM stores WHERE osm_type=:type AND osm_id=:osm")
                    .param("type",row.osmType()).param("osm",row.osmId()).query(Long.class).optional().orElse(null) : null;
            boolean review=product==null
                    || "UNKNOWN".equals(scope) || ("BRANCH".equals(scope) && branch==null);
            String key=key(row);
            if (review) files.review(source,key,product==null ? "Product requires an exact match" : "Scope or retailer requires review",row.name(),dry,report);
            if (dry) {
                if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM grocery_scoped_prices WHERE source=:source AND source_key=:key)")
                        .param("source",source).param("key",key).query(Boolean.class).single()) report.skip(null);
                else if (!review) report.imported++;
                continue;
            }
            int inserted=jdbc.sql("""
                INSERT INTO grocery_scoped_prices(source,source_key,product_id,retailer_id,barcode,retailer_sku,product_name,
                  brand,package_size,retailer_key,scope,scope_label,branch_id,amount,regular_amount,promotional_amount,
                  membership_amount,currency,observed_at,observed_on,source_url,promotion_terms,loyalty_required,in_stock)
                VALUES(:source,:key,:product,:retailer,:barcode,:sku,:name,:brand,:size,:retailerKey,:scope,
                  :label,:branch,:amount,:regular,:promotion,:member,'ZAR',:observedAt,:observedOn,:url,:terms,:loyalty,:stock)
                ON CONFLICT(source,source_key) DO NOTHING
                """).paramSource(new MapSqlParameterSource("source",source).addValue("key",key)
                    .addValue("product",product,Types.BIGINT).addValue("retailer",retailer,Types.BIGINT)
                    .addValue("barcode",code).addValue("sku",clean(row.sku(),100)).addValue("name",clean(row.name(),255))
                    .addValue("brand",clean(row.brand(),100)).addValue("size",clean(row.size(),100))
                    .addValue("retailerKey",clean(row.retailer(),100)).addValue("scope",scope)
                    .addValue("label",clean(row.scopeLabel(),100)).addValue("branch",branch,Types.BIGINT)
                    .addValue("amount",row.amount()).addValue("regular",row.regular()).addValue("promotion",row.promotional())
                    .addValue("member",row.member()).addValue("observedAt",date(row.observedAt()),Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("observedOn",row.observedOn(),Types.DATE).addValue("url",url(row.url()))
                    .addValue("terms",clean(row.terms(),500)).addValue("loyalty",row.loyaltyRequired())
                    .addValue("stock",row.inStock())).update();
            if (inserted==1 && !review) report.imported++;
            else if (inserted==0) {
                jdbc.sql("""
                    UPDATE grocery_scoped_prices SET product_id=coalesce(product_id,:product),
                        retailer_id=coalesce(retailer_id,:retailer),branch_id=coalesce(branch_id,:branch)
                    WHERE source=:source AND source_key=:key
                      AND ((product_id IS NULL AND :product IS NOT NULL)
                         OR (retailer_id IS NULL AND :retailer IS NOT NULL) OR (branch_id IS NULL AND :branch IS NOT NULL))
                    """).param("source",source).param("key",key).param("product",product,Types.BIGINT)
                        .param("retailer",retailer,Types.BIGINT).param("branch",branch,Types.BIGINT).update();
                report.skip(null);
            }
            if (!review) jdbc.sql("DELETE FROM grocery_import_review WHERE source=:source AND source_key=:key")
                    .param("source",source).param("key",key).update();
        }
        return report;
    }

    static Long approvedAlias(JdbcClient jdbc, Long retailer,PriceDataSource.Price row) {
        if (retailer==null || row.name()==null || row.brand()==null || row.size()==null) return null;
        return jdbc.sql("""
            SELECT DISTINCT p.id FROM product_aliases a JOIN products p ON p.id=a.product_id
            WHERE a.retailer_id=:retailer AND lower(a.raw_name)=lower(:name)
               AND a.confidence>=.95 AND a.matched_by IN ('BARCODE','MANUAL')
               AND lower(p.brand)=lower(:brand) AND lower(p.size_text)=lower(:size)
            LIMIT 2
            """).param("retailer",retailer).param("name",row.name()).param("brand",row.brand()).param("size",row.size())
                .query(Long.class).list().stream().reduce((first,second) -> -1L).filter(id -> id>0).orElse(null);
    }

    static String retailerSlug(String name) {
        if (name==null) return null;
        return switch(name.strip().toLowerCase(Locale.ROOT)) {
            case "pnp","pick n pay" -> "picknpay";
            case "food lover's market","foodloversmarket" -> "flm";
            default -> name.strip().toLowerCase(Locale.ROOT);
        };
    }

    private static String key(PriceDataSource.Price row) {
        if (row.providerId()!=null) return clean(row.providerId(),180);
        var source=java.util.stream.Stream.of(row.retailer(),row.barcode(),row.sku(),row.name(),row.brand(),row.size(),
                row.scope(),row.scopeLabel(),row.observedAt(),row.observedOn(),row.amount().stripTrailingZeros(),
                row.regular(),row.promotional(),row.member(),row.terms(),row.loyaltyRequired(),row.inStock())
                .map(value -> Objects.toString(value,"")).toList().toString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static OffsetDateTime date(Instant value) { return value==null ? null : value.atOffset(ZoneOffset.UTC); }
}
