package za.co.shoppingdiary.ingestion.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static za.co.shoppingdiary.ingestion.automation.SourceFields.*;

@Service
@RequiredArgsConstructor
public class LoyaltyCatalogue {
    private final JdbcClient jdbc;
    private final BulkGroceryFiles files;
    private final ObjectMapper json;

    @Value("${LOYALTYHUB_AUTHORIZED_IMAGE_RETAILERS:}")
    private String imageRetailers;

    public ImportCounts accept(List<PriceDataSource.Price> rows, boolean dry, Long genericId) {
        var counts = new ImportCounts();
        for (var row : rows) {
            counts.examined++;
            if (clean(row.name(),255)==null || row.amount()==null || row.observedAt()==null
                    || row.observedAt().isAfter(Instant.now()) || !"ZAR".equalsIgnoreCase(row.currency())
                    || row.scope()==null || !List.of("ONLINE","REGIONAL","CATALOGUE").contains(row.scope())) {
                counts.skip("Invalid product, pricing scope, currency or provider timestamp");
                continue;
            }
            String slug = ScopedPriceIntake.retailerSlug(row.retailer());
            Long retailer = jdbc.sql("SELECT id FROM retailers WHERE active AND slug=:slug")
                    .param("slug",slug).query(Long.class).optional().orElse(null);
            String barcode = row.barcode()!=null && row.barcode().matches("[0-9]{8,14}") ? row.barcode() : null;
            String sku = clean(row.sku(),100);
            Long alias=retailer==null || barcode!=null ? null : ScopedPriceIntake.approvedAlias(jdbc,retailer,row);
            if (retailer==null || (barcode==null && sku==null && alias==null)) {
                review(row, "Missing stable product identity or unknown retailer", dry, counts);
                continue;
            }
            Long barcodeMatch = barcode==null ? null : jdbc.sql("SELECT id FROM products WHERE barcode=:barcode")
                    .param("barcode",barcode).query(Long.class).optional().orElse(null);
            Long skuMatch = sku==null ? null : jdbc.sql("""
                SELECT product_id FROM loyalty_product_identity WHERE retailer_id=:retailer AND sku=:sku
                """).param("retailer",retailer).param("sku",sku).query(Long.class).optional().orElse(null);
            if ((skuMatch!=null && barcode!=null && jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM products WHERE id=:id AND barcode IS NOT NULL AND barcode<>:barcode)
                    """).param("id",skuMatch).param("barcode",barcode).query(Boolean.class).single())
                    || (barcodeMatch!=null && skuMatch!=null && !barcodeMatch.equals(skuMatch))) {
                review(row, "Barcode and retailer SKU identify different products", dry, counts);
                continue;
            }
            Long product = barcodeMatch!=null ? barcodeMatch : skuMatch!=null ? skuMatch : alias;
            String name = clean(row.name(),255);
            String brand = clean(row.brand(),100);
            String size = clean(row.size(),50);
            if (product==null && barcode==null && jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM products WHERE lower(canonical_name)=lower(:name)
                        AND lower(coalesce(brand,''))=lower(coalesce(:brand,''))
                        AND lower(coalesce(size_text,''))=lower(coalesce(:size,'')))
                    """).param("name",name).param("brand",brand).param("size",size).query(Boolean.class).single()) {
                review(row, "Existing product has the same name, brand and size", dry, counts);
                continue;
            }
            if (dry) {
                counts.imported++;
                continue;
            }

            var attributes = json.createObjectNode();
            var raw = row.attributes();
            if (raw!=null) {
                for (String field : List.of("name","description","brand","manufacturer","department","category","subcategory",
                        "product_id","variant","flavour","unit_size","unit_price","unit_price_measurement","pack_count",
                        "price_changed_at","on_promotion","gtin","size","image_url","thumbnail_url","image","images")) {
                    var value = raw.get(field);
                    if (value!=null && (value.isValueNode() || List.of("images","size","unit_price").contains(field)) && value.toString().length()<8000)
                        attributes.set(field,value);
                }
            }
            String image = Arrays.stream(imageRetailers.split(",")).anyMatch(item -> item.strip().equalsIgnoreCase(slug))
                    ? url(text(attributes,"image_url")) : null;
            var sourceSize=attributes.path("size");
            var quantity=sourceSize.path("quantity");
            String baseUnit=text(sourceSize,"unit");
            var baseQuantity=quantity.isNumber() ? quantity.decimalValue() : null;
            if (baseUnit!=null && List.of("item","items","each").contains(baseUnit)) baseUnit="count";
            if (baseUnit==null || !List.of("kg","g","l","ml","count").contains(baseUnit)
                    || baseQuantity==null || baseQuantity.signum()<=0 || baseQuantity.precision()>12
                    || baseQuantity.scale()>4 || baseQuantity.precision()-baseQuantity.scale()>8) {
                baseUnit=null;
                baseQuantity=null;
            }
            if (product==null) {
                product = jdbc.sql("""
                    INSERT INTO products(canonical_name,brand,barcode,size_text,image_url,category,base_quantity,base_unit)
                    VALUES (:name,:brand,:barcode,:size,:image,:category,:quantity,:unit)
                    ON CONFLICT(barcode) DO UPDATE SET barcode=excluded.barcode
                    RETURNING id
                    """).param("name",name).param("brand",brand).param("barcode",barcode)
                        .param("size",size).param("image",image).param("quantity",baseQuantity).param("unit",baseUnit).param("category",clean(text(attributes,"category"),100))
                        .query(Long.class).single();
            }
            if (sku!=null) jdbc.sql("""
                INSERT INTO loyalty_product_identity(retailer_id,sku,product_id) VALUES (:retailer,:sku,:product)
                ON CONFLICT(retailer_id,sku) DO NOTHING
                """).param("retailer",retailer).param("sku",sku).param("product",product).update();
            if (barcode!=null && barcodeMatch==null && skuMatch!=null) jdbc.sql("""
                UPDATE products SET barcode=:barcode WHERE id=:product AND barcode IS NULL
                """).param("barcode",barcode).param("product",product).update();
            if (genericId!=null) jdbc.sql("""
                INSERT INTO loyalty_generic_variants(generic_id,product_id) VALUES (:generic,:product)
                ON CONFLICT DO NOTHING
                """).param("generic",genericId).param("product",product).update();
            var unitPrice=attributes.path("unit_price");
            var unitValue=unitPrice.path("value");
            var unitAmount=unitValue.isNumber() ? unitValue.decimalValue() : null;
            if (unitAmount!=null && (unitAmount.signum()<=0 || unitAmount.scale()>6
                    || unitAmount.precision()-unitAmount.scale()>10)) unitAmount=null;
            int updated = jdbc.sql("""
                INSERT INTO loyalty_latest_prices(product_id,retailer_id,scope,scope_label,amount,regular_amount,
                    promotional_amount,membership_amount,in_stock,observed_at,source_url,promotion_terms,loyalty_required,
                    provider_price_changed_at,unit_price_amount,unit_price_measurement,provider_on_promotion)
                VALUES (:product,:retailer,:scope,:label,:amount,:regular,:promo,:member,:stock,:observed,:url,:terms,:loyalty,
                    :changed,:unitAmount,:per,:onPromotion)
                ON CONFLICT(product_id,retailer_id,scope,scope_label) DO UPDATE SET
                    amount=excluded.amount, regular_amount=excluded.regular_amount, promotional_amount=excluded.promotional_amount,
                    membership_amount=excluded.membership_amount, in_stock=excluded.in_stock, observed_at=excluded.observed_at,
                    collected_at=now(), source_url=excluded.source_url, promotion_terms=excluded.promotion_terms,
                    loyalty_required=excluded.loyalty_required, promotion_expires_at=NULL,
                    provider_price_changed_at=excluded.provider_price_changed_at,unit_price_amount=excluded.unit_price_amount,
                    unit_price_measurement=excluded.unit_price_measurement,provider_on_promotion=excluded.provider_on_promotion
                WHERE excluded.observed_at > loyalty_latest_prices.observed_at
                """).paramSource(new MapSqlParameterSource("product",product).addValue("retailer",retailer)
                        .addValue("scope",row.scope()).addValue("label",java.util.Objects.toString(
                                clean(row.scopeLabel()==null ? null : row.scopeLabel().toLowerCase(Locale.ROOT),100),""))
                        .addValue("amount",row.amount()).addValue("regular",row.regular()).addValue("promo",row.promotional())
                        .addValue("member",row.member()).addValue("stock",row.inStock())
                        .addValue("observed",ScopedPriceIntake.date(row.observedAt())).addValue("url",url(row.url()))
                        .addValue("terms",clean(row.terms(),500)).addValue("loyalty",row.loyaltyRequired())
                        .addValue("changed",ScopedPriceIntake.date(instant(text(attributes,"price_changed_at"))),java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("unitAmount",unitAmount).addValue("per",clean(text(unitPrice,"per"),20))
                        .addValue("onPromotion",flag(attributes,"on_promotion"))).update();
            int metadataUpdated = jdbc.sql("""
                INSERT INTO loyalty_product_metadata(product_id,provider_updated_at,attributes)
                VALUES (:product,:updated,cast(:attributes AS jsonb))
                ON CONFLICT(product_id) DO UPDATE SET provider_updated_at=excluded.provider_updated_at,
                    attributes=loyalty_product_metadata.attributes || excluded.attributes
                WHERE excluded.provider_updated_at > loyalty_product_metadata.provider_updated_at
                """).param("product",product).param("updated",ScopedPriceIntake.date(row.observedAt()))
                    .param("attributes",attributes.toString()).update();
            if (metadataUpdated>0 && image!=null) jdbc.sql("""
                UPDATE products SET image_url=:image,image_source_url=:source WHERE id=:product
                AND (image_url IS NULL OR image_source_url=:source)
                """).param("image",image).param("source","https://loyaltyhub.co.za/")
                    .param("product",product).update();
            jdbc.sql("DELETE FROM grocery_import_review WHERE source='LOYALTYHUB' AND source_key=:key")
                    .param("key",reviewKey(row)).update();
            if (updated>0) counts.imported++;
            else counts.skip(null);
        }
        return counts;
    }

    private void review(PriceDataSource.Price row, String reason, boolean dry, ImportCounts counts) {
        files.review("LOYALTYHUB",reviewKey(row),reason,row.name(),dry,counts);
    }

    private static String reviewKey(PriceDataSource.Price row) {
        return java.util.UUID.nameUUIDFromBytes((row.retailer()+"|"+row.sku()+"|"+row.barcode()+"|"+row.name())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
