package za.co.shoppingdiary.ingestion.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import za.co.shoppingdiary.ingestion.api.service.OpenFoodFactsService;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.List;
import javax.xml.stream.*;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

import static za.co.shoppingdiary.ingestion.automation.SourceFields.*;

@Service
@RequiredArgsConstructor
public class BulkGroceryFiles {
    static final String CATALOGUE="OPEN_FOOD_FACTS";
    static final String BRANCHES="OPEN_STREET_MAP";
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final OpenFoodFactsService off;

    public ImportCounts catalogue(InputStream input,String name,boolean dry) throws IOException {
        var report=new ImportCounts();
        try (var reader=reader(input)) {
            if (name.matches("(?i).*\\.(tsv|csv)(\\.gz)?$")) {
                try (var csv=CSVFormat.TDF.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
                    if (!csv.getHeaderMap().keySet().containsAll(List.of("code","countries_tags","product_name"))) {
                        throw new IOException("Expected the official tab-separated Open Food Facts export");
                    }
                    for (var row : csv) {
                        report.examined++;
                        catalogueRow(csvProduct(row),dry,report);
                    }
                }
            } else {
                var line=reader.readLine();
                if (line==null) throw new IOException("Catalogue export is empty");
                do {
                    if (line.isBlank()) continue;
                    report.examined++;
                    try { catalogueRow(json.readTree(line),dry,report); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                        report.skip("Malformed catalogue JSON at row "+report.examined);
                    }
                } while ((line=reader.readLine())!=null);
            }
        }
        return report;
    }

    private JsonNode csvProduct(CSVRecord row) {
        var product=json.createObjectNode();
        for (String key : List.of("code","product_name","product_name_en","brands","quantity","image_front_url","image_url")) {
            if (row.isMapped(key) && row.isSet(key)) product.put(key,row.get(key));
        }
        for (String key : List.of("countries_tags","categories_tags")) {
            var array=product.putArray(key);
            if (row.isMapped(key) && row.isSet(key)) for (String tag : row.get(key).split(",")) array.add(tag.strip());
        }
        return product;
    }

    private void catalogueRow(JsonNode row,boolean dry,ImportCounts report) {
        var product=off.exportCandidate(row);
        if (product==null) { report.skip(null); return; }
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM products WHERE barcode=:code)")
                .param("code",product.barcode()).query(Boolean.class).single()) {
            report.skip(null); return;
        }
        if (product.brand()!=null && product.sizeText()!=null && jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM products WHERE barcode IS NULL
                  AND lower(canonical_name)=lower(:name) AND lower(brand)=lower(:brand) AND lower(size_text)=lower(:size))
            """).param("name",product.name()).param("brand",product.brand()).param("size",product.sizeText())
                .query(Boolean.class).single()) {
            review(CATALOGUE,product.barcode(),"Possible match to an existing unbarcoded product",product.name(),dry,report);
            return;
        }
        int count=dry ? 1 : jdbc.sql("""
            INSERT INTO products(barcode,canonical_name,brand,size_text,category_id,image_url,catalogue_source_url,image_source_url)
            VALUES(:code,:name,:brand,:size,(SELECT id FROM categories WHERE lower(name)=lower(:category) AND depth=0 LIMIT 1),
                   :image,:source,CASE WHEN :image IS NOT NULL THEN :source END)
            ON CONFLICT (barcode) DO NOTHING
            """).param("code",product.barcode()).param("name",product.name()).param("brand",product.brand())
                .param("size",product.sizeText()).param("category",product.department()).param("image",product.imageUrl(),Types.VARCHAR)
                .param("source",product.sourceUrl()).update();
        if (count==1) report.imported++; else report.skip(null);
    }

    public ImportCounts branches(InputStream input,String name,boolean dry) throws IOException {
        var report=new ImportCounts();
        try (var reader=reader(input)) {
            if (name.matches("(?i).*\\.osm(\\.gz)?$")) {
                xmlBranches(reader,dry,report);
            } else if (name.matches("(?i).*\\.(json|json\\.gz)$") && !name.matches("(?i).*geojson.*")) {
                var root=json.readTree(reader);
                if (root==null || !root.path("elements").isArray() || root.has("remark")) {
                    throw new IOException("Expected a complete Overpass JSON extract");
                }
                for (var node : root.path("elements")) {
                    report.examined++;
                    var center=node.has("center") ? node.path("center") : node;
                    var geometry=json.createObjectNode().put("type","Point");
                    geometry.putArray("coordinates").add(center.path("lon").asDouble(Double.NaN)).add(center.path("lat").asDouble(Double.NaN));
                    branchRow(SourceFields.text(node,"type"),node.path("id").asLong(),node.path("tags"),
                            geometry,timestamp(node.path("timestamp")),dry,report);
                }
            } else {
                String line;
                while ((line=reader.readLine())!=null) {
                    line=line.strip().replaceFirst("^\\x1e","");
                    if (line.isBlank()) continue;
                    report.examined++;
                    try {
                        var row=json.readTree(line);
                        var tags=row.path("properties");
                        String type=text(tags,"@type");
                        long id=tags.path("@id").asLong();
                        if (type==null || id<=0) {
                            String featureId=row.path("id").asText();
                            if (!featureId.matches("[nwr][0-9]{1,18}")) {
                                report.skip("Missing OSM object type or ID at row "+report.examined);
                                continue;
                            }
                            type=switch(featureId.charAt(0)) {
                                case 'n' -> "node"; case 'w' -> "way"; default -> "relation";
                            };
                            id=Long.parseLong(featureId.substring(1));
                        }
                        branchRow(type,id,tags,row.path("geometry"),timestamp(tags.path("@timestamp")),dry,report);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { report.skip("Malformed OSM row "+report.examined); }
                }
            }
        }
        return report;
    }

    private void xmlBranches(Reader reader,boolean dry,ImportCounts report) throws IOException {
        var factory=XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        var nodes=new HashMap<Long,double[]>();
        try {
            var xml=factory.createXMLStreamReader(reader);
            try {
                while (xml.hasNext()) {
                    if (xml.next()!=XMLStreamConstants.START_ELEMENT) continue;
                    String type=xml.getLocalName();
                    if (!List.of("node","way","relation").contains(type)) continue;
                    long id=Long.parseLong(xml.getAttributeValue(null,"id"));
                    var updated=instant(xml.getAttributeValue(null,"timestamp"));
                    boolean visible=!"false".equals(xml.getAttributeValue(null,"visible"));
                    var tags=json.createObjectNode();
                    var refs=new ArrayList<Long>();
                    double[] coordinates=null;
                    if ("node".equals(type)) {
                        coordinates=new double[]{Double.parseDouble(xml.getAttributeValue(null,"lon")),
                                Double.parseDouble(xml.getAttributeValue(null,"lat"))};
                        nodes.put(id,coordinates);
                        if (nodes.size()>250_000) throw new IOException("Use streamed GeoJSON sequences for large OSM extracts");
                    }
                    while (xml.hasNext()) {
                        int event=xml.next();
                        if (event==XMLStreamConstants.END_ELEMENT && type.equals(xml.getLocalName())) break;
                        if (event!=XMLStreamConstants.START_ELEMENT) continue;
                        if ("tag".equals(xml.getLocalName())) tags.put(xml.getAttributeValue(null,"k"),xml.getAttributeValue(null,"v"));
                        if ("nd".equals(xml.getLocalName())) refs.add(Long.parseLong(xml.getAttributeValue(null,"ref")));
                    }
                    if (!visible || !"supermarket".equals(text(tags,"shop"))) continue;
                    report.examined++;
                    if (coordinates==null) {
                        var points=refs.stream().distinct().map(nodes::get).filter(java.util.Objects::nonNull).toList();
                        if (points.isEmpty() || points.size()!=refs.stream().distinct().count()) {
                            review(BRANCHES,type+"/"+id,"OSM extract lacks complete store geometry",text(tags,"name"),dry,report);
                            continue;
                        }
                        coordinates=new double[]{points.stream().mapToDouble(point -> point[0]).average().orElseThrow(),
                                points.stream().mapToDouble(point -> point[1]).average().orElseThrow()};
                    }
                    var geometry=json.createObjectNode().put("type","Point");
                    geometry.putArray("coordinates").add(coordinates[0]).add(coordinates[1]);
                    branchRow(type,id,tags,geometry,updated,dry,report);
                }
            } finally { xml.close(); }
        } catch (XMLStreamException | IllegalArgumentException ex) { throw new IOException("Invalid OSM XML export",ex); }
    }


    private void branchRow(String type,long id,JsonNode tags,JsonNode geometry,java.time.Instant updated,boolean dry,ImportCounts report) {
        if (type==null || !List.of("node","way","relation").contains(type) || id<=0
                || !"supermarket".equals(text(tags,"shop")) || !geometry.isObject()) { report.skip(null); return; }
        String key=type+"/"+id;
        String name=clean(text(tags,"name"),255);
        String brand=text(tags,"brand");
        if (brand==null) brand=text(tags,"operator");
        if (brand==null) brand=name;
        if (name==null || brand==null || tags.has("disused:shop") || tags.has("abandoned:shop")
                || tags.has("closed") || tags.has("demolished:shop") || "yes".equals(text(tags,"disused"))) {
            review(BRANCHES,key,"Missing name or uncertain operating status",name,dry,report);
            return;
        }
        boolean stale=updated!=null && updated.isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(365*5L)));
        String country=text(tags,"addr:country");
        if (country!=null && !"ZA".equalsIgnoreCase(country) && !"South Africa".equalsIgnoreCase(country)) {
            report.skip(null); return;
        }
        var retailer=jdbc.sql("""
            SELECT id FROM retailers WHERE active AND regexp_replace(lower(name),'[^a-z0-9]','','g')
                 =CASE regexp_replace(lower(:brand),'[^a-z0-9]','','g')
                     WHEN 'woolworthsfood' THEN 'woolworths' WHEN 'checkershyper' THEN 'checkers'
                     ELSE regexp_replace(lower(:brand),'[^a-z0-9]','','g') END
            """).param("brand",brand).query(Long.class).optional();
        if (retailer.isEmpty()) { review(BRANCHES,key,"Unmapped retailer identity",name,dry,report); return; }
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM stores WHERE osm_type=:type AND osm_id=:osm)")
                .param("type",type).param("osm",id).query(Boolean.class).single()) {
            report.skip(null); return;
        }
        if ("Point".equals(geometry.path("type").asText())) {
            var coordinates=geometry.path("coordinates");
            double lon=coordinates.path(0).asDouble(Double.NaN), lat=coordinates.path(1).asDouble(Double.NaN);
            if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon<16 || lon>33 || lat<-35 || lat>-22) {
                review(BRANCHES,key,"Missing or out-of-area coordinates",name,dry,report); return;
            }
        } else if (!List.of("Polygon","MultiPolygon").contains(geometry.path("type").asText())) {
            review(BRANCHES,key,"Unsupported store geometry",name,dry,report); return;
        }
        var point=jdbc.sql("""
            SELECT ST_AsText(pt) FROM (SELECT ST_PointOnSurface(ST_GeomFromGeoJSON(:geometry)) AS pt) input
            WHERE ST_X(pt) BETWEEN 16 AND 33 AND ST_Y(pt) BETWEEN -35 AND -22
            """).param("geometry",geometry.toString()).query(String.class).optional();
        if (point.isEmpty()) { review(BRANCHES,key,"Missing or out-of-area coordinates",name,dry,report); return; }
        var nearby=jdbc.sql("""
            SELECT id,name,osm_id FROM stores
            WHERE retailer_id=:retailer AND upper(store_code) IS DISTINCT FROM 'ONLINE'
                AND ST_DWithin(location::geography,ST_GeomFromText(:point,4326)::geography,80)
            ORDER BY id LIMIT 2
            """).param("retailer",retailer.get()).param("point",point.get()).query(Nearby.class).list();
        if (!nearby.isEmpty()) {
            var match=nearby.getFirst();
            if (nearby.size()!=1 || match.osmId()!=null || !match.name().equalsIgnoreCase(name)) {
                review(BRANCHES,key,"Possible duplicate of a registered nearby branch",name,dry,report); return;
            }
            int count=dry ? 1 : jdbc.sql("""
                UPDATE stores SET osm_type=:type,osm_id=:osm,osm_checked_at=now()
                WHERE id=:id AND osm_id IS NULL
                """).param("type",type).param("osm",id).param("id",match.id()).update();
            if (count==1) report.imported++; else report.skip(null);
            return;
        }
        var address=clean(String.join(" ",List.of(safe(tags,"addr:housenumber"),safe(tags,"addr:street"))),500);
        int count=dry ? 1 : jdbc.sql("""
            INSERT INTO stores(retailer_id,name,store_code,address,location,city,province,postal_code,active,
                               source_url,osm_type,osm_id,osm_checked_at)
            VALUES(:retailer,:name,:code,:address,ST_GeomFromText(:point,4326),:city,:province,:postal,:active,
                   :source,:type,:osm,now()) ON CONFLICT (osm_type,osm_id) DO NOTHING
            """).param("retailer",retailer.get()).param("name",name).param("code","osm-"+type+"-"+id)
                .param("address",address).param("point",point.get()).param("city",clean(text(tags,"addr:city"),100))
                .param("province",clean(text(tags,"addr:province"),100)).param("postal",clean(text(tags,"addr:postcode"),10))
                .param("active",!stale).param("source","https://www.openstreetmap.org/"+key).param("type",type).param("osm",id).update();
        if (count==1) {
            report.imported++;
            if (stale) review(BRANCHES,key,"OSM object has not been edited in five years",name,dry,report);
        } else report.skip(null);
    }

    private static java.time.Instant timestamp(JsonNode value) {
        return value.isIntegralNumber() ? java.time.Instant.ofEpochSecond(value.asLong()) : instant(value.asText(null));
    }

    private record Nearby(long id,String name,Long osmId) {}

    void review(String source,String key,String reason,String label,boolean dry,ImportCounts report) {
        report.review++;
        report.sample(reason+": "+(label==null ? key : label));
        if (!dry) jdbc.sql("""
            INSERT INTO grocery_import_review(source,source_key,reason,label) VALUES(:source,:key,:reason,:label)
            ON CONFLICT(source,source_key) DO UPDATE SET reason=excluded.reason,label=excluded.label
            """).param("source",source).param("key",key).param("reason",reason).param("label",clean(label,255)).update();
    }

    static BufferedReader reader(InputStream input) throws IOException {
        var stream=new PushbackInputStream(input,2);
        var magic=stream.readNBytes(2);
        stream.unread(magic);
        var data=magic.length==2 && (magic[0]&255)==0x1f && (magic[1]&255)==0x8b ? new GZIPInputStream(stream) : stream;
        return new BufferedReader(new InputStreamReader(data,StandardCharsets.UTF_8));
    }

    private static String safe(JsonNode node,String key) {
        var text=SourceFields.text(node,key); return text==null ? "" : text;
    }
}
