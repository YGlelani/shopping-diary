package za.co.shoppingdiary.ingestion.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static za.co.shoppingdiary.ingestion.automation.SourceFields.*;

@Component
@RequiredArgsConstructor
public class OpenPricesSource implements PriceDataSource {
    private final ObjectMapper json;
    @Value("${GROCERY_OPEN_PRICES_FILE:}") private String prices;
    @Value("${GROCERY_OPEN_PRICES_LOCATIONS_FILE:}") private String locations;

    public String name() { return "OPEN_PRICES"; }
    public boolean enabled() { return !prices.isBlank() && !locations.isBlank(); }

    private record Location(String osmType,Long osmId,String name) {}

    @Override
    public Page collect(String query,int offset,Instant changedSince) {
        if (!enabled()) throw new IllegalArgumentException("Configure the official Open Prices and location exports");
        try {
            var places=new HashMap<Long,Location>();
            try (var input=BulkGroceryFiles.reader(Files.newInputStream(Path.of(locations)))) {
                String line;
                while ((line=input.readLine())!=null) {
                    if (line.isBlank()) continue;
                    var row=json.readTree(line);
                    if (!"za".equalsIgnoreCase(text(row,"osm_address_country_code"))) continue;
                    String type=text(row,"osm_type");
                    if (type!=null) type=type.toLowerCase(java.util.Locale.ROOT);
                    long osm=row.path("osm_id").asLong();
                    if (type==null || !List.of("node","way","relation").contains(type) || osm<=0) continue;
                    places.put(row.path("id").asLong(),new Location(type,osm,text(row,"osm_name")));
                }
            }
            var rows=new ArrayList<Price>();
            Instant fileUpdated=Files.getLastModifiedTime(Path.of(prices)).toInstant();
            try (var input=BulkGroceryFiles.reader(Files.newInputStream(Path.of(prices)))) {
                String line;
                while ((line=input.readLine())!=null) {
                    if (line.isBlank()) continue;
                    var row=json.readTree(line);
                    var location=places.get(row.path("location_id").asLong());
                    if (location==null || !"ZAR".equals(text(row,"currency")) || !row.path("duplicate_of").isNull()
                            && row.has("duplicate_of")) continue;
                    long id=row.path("id").asLong();
                    if (id<=0 || price(row,"price")==null) continue;
                    String barcode=text(row,"product_code");
                    if (barcode==null && row.path("product_code").isIntegralNumber()) barcode=row.path("product_code").asText();
                    var amount=price(row,"price");
                    rows.add(new Price(Long.toString(id),barcode,null,text(row,"product_name"),null,null,null,
                        "BRANCH",location.name(),location.osmType(),location.osmId(),amount,price(row,"price_without_discount"),
                        Boolean.TRUE.equals(flag(row,"price_is_discounted")) ? amount : null,null,"ZAR",null,
                        observedDate(row),"https://prices.openfoodfacts.org/prices/"+id,null,null,null));
                }
            }
            return new Page(List.copyOf(rows),null,fileUpdated);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read the configured Open Prices exports");
        }
    }

    private static LocalDate observedDate(JsonNode row) {
        var day=text(row,"date");
        if (day==null) return null;
        try { return LocalDate.parse(day); }
        catch (java.time.DateTimeException ex) { return null; }
    }
}
