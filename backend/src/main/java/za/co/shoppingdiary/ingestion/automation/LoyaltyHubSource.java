package za.co.shoppingdiary.ingestion.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static za.co.shoppingdiary.ingestion.automation.SourceFields.*;

@Component
@RequiredArgsConstructor
public class LoyaltyHubSource implements PriceDataSource {
    private static final String BASE = "https://loyaltyhub.co.za/api/v1/";
    private final LoyaltyHubQuota quota;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Value("${LOYALTYHUB_FREE_TIER_ENABLED:false}") private boolean optedIn;
    @Value("${loyaltyhub.api.key:${LOYALTYHUB_API_KEY:}}") private String key;
    @Value("${LOYALTYHUB_PAGE_SIZE:10}") private int pageSize;

    public String name() { return "LOYALTYHUB"; }
    public boolean enabled() { return optedIn && key!=null && !key.isBlank(); }

    public record Filters(String department,String retailer,String region,String barcode) {
        public Filters {
            for (String value : new String[]{department,retailer,region}) {
                if (value!=null && !value.matches("[a-z0-9_-]{1,70}"))
                    throw new IllegalArgumentException("Invalid Loyalty Hub filter");
            }
            if (barcode!=null && !barcode.matches("[0-9]{8,14}"))
                throw new IllegalArgumentException("Invalid barcode");
        }
    }

    public enum Reference {
        DEPARTMENTS("departments","id",false),
        RETAILERS("retailers","id",true),
        REGIONS("regions","region",false);

        final String endpoint;
        final String identifier;
        final boolean authenticated;
        Reference(String endpoint,String identifier,boolean authenticated) {
            this.endpoint=endpoint;
            this.identifier=identifier;
            this.authenticated=authenticated;
        }
    }

    public JsonNode reference(Reference reference) {
        var builder=UriComponentsBuilder.fromUriString(BASE+reference.endpoint);
        if (reference.authenticated) builder.queryParam("limit",limit());
        var data=fetch(builder,reference.authenticated,false,10).path("data");
        if (!data.isArray() || data.isEmpty() || data.size()>100) throw invalidResponse();
        return data;
    }

    @Override
    public Page collect(String query,int offset,Instant changedSince) {
        return collect(query,offset,changedSince,false);
    }

    public Page collect(String query,int offset,Instant changedSince,boolean refreshing) {
        return collect(query,offset,changedSince,refreshing,new Filters(null,null,null,null));
    }

    public Page collect(String query,int offset,Instant changedSince,boolean refreshing,Filters filters) {
        var builder=UriComponentsBuilder.fromUriString(BASE+"prices")
                .queryParam("limit",limit()).queryParam("offset",offset).queryParam("max_age_days",7);
        if (query!=null && !query.isBlank()) builder.queryParam("search",query);
        if (filters.department()!=null) builder.queryParam("department",filters.department());
        if (filters.retailer()!=null) builder.queryParam("retailer",filters.retailer());
        if (filters.region()!=null) builder.queryParam("region",filters.region());
        if (filters.barcode()!=null) builder.queryParam("barcode",filters.barcode());
        if (changedSince!=null) builder.queryParam("changed_since",changedSince);
        var response=fetch(builder,true,refreshing,limit());
        var data=response.path("data");
        if (!data.isArray()) throw invalidResponse();
        return pricePage(data,response.path("meta"),offset);
    }

    public Page barcode(String barcode,String region) {
        var filters=new Filters(null,null,region,barcode);
        if (barcode==null) throw new IllegalArgumentException("A barcode is required");
        var builder=UriComponentsBuilder.fromUriString(BASE+"products").queryParam("barcode",barcode)
                .queryParam("limit",limit()).queryParam("max_age_days",7);
        if (filters.region()!=null) builder.queryParam("region",region);
        var response=fetch(builder,true,true,10);
        var product=response.path("data");
        if (product.isNull()) return new Page(List.of(),null,instant(text(response.path("meta"),"updated_at")));
        if (!product.isObject() || !product.path("offers").isArray()) throw invalidResponse();
        var rows=new ArrayList<Price>();
        for (var offer : product.path("offers")) {
            if (!offer.isObject()) throw invalidResponse();
            var row=json.createObjectNode();
            row.setAll((com.fasterxml.jackson.databind.node.ObjectNode)product);
            row.remove("offers");
            row.setAll((com.fasterxml.jackson.databind.node.ObjectNode)offer);
            rows.add(mapPrice(row));
        }
        return new Page(rows,null,instant(text(response.path("meta"),"updated_at")));
    }

    static Page pricePage(JsonNode data,JsonNode metadata,int offset) {
        var next=metadata.path("next_offset");
        boolean hasMore=metadata.path("has_more").asBoolean(false);
        if (hasMore && (!next.isIntegralNumber() || !next.canConvertToInt() || next.asInt()<=offset))
            throw invalidResponse();
        var rows=new ArrayList<Price>();
        for (var row : data) {
            if (!row.isObject()) throw invalidResponse();
            rows.add(mapPrice(row));
        }
        return new Page(rows,hasMore ? next.asInt() : null,instant(text(metadata,"updated_at")));
    }

    private static Price mapPrice(JsonNode row) {
        String region=text(row,"region");
        String basis=text(row,"price_basis");
        String scope=region!=null && "regional_store".equals(basis) ? "REGIONAL" : "online".equals(basis) ? "ONLINE"
                : "single_store".equals(basis) ? "CATALOGUE" : "UNKNOWN";
        var regular=price(row,"was_price");
        var amount=price(row,"price");
        Boolean reduced=flag(row,"on_promotion");
        if (Boolean.FALSE.equals(reduced) && regular==null) regular=amount;
        var promotional=Boolean.TRUE.equals(reduced) ? amount : null;
        String barcode=text(row,"barcode");
        if (barcode==null || !barcode.matches("[0-9]{8,14}")) barcode=text(row,"gtin");
        return new Price(text(row,"product_id"),barcode,text(row,"retailer_sku"),text(row,"name"),
                text(row,"brand"),text(row,"unit_size"),text(row,"retailer"),scope,region==null ? basis : region,
                null,null,amount,regular,promotional,price(row,"loyalty_price"),text(row,"currency"),
                instant(text(row,"last_seen_at")),null,url(text(row,"product_url")),
                text(row,"promotion"),flag(row,"promotion_loyalty"),flag(row,"in_stock"),row);
    }

    private JsonNode fetch(UriComponentsBuilder target,boolean authenticated,boolean refreshing,int reservedRows) {
        if (!enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"A confirmed free-tier Loyalty Hub account is required");
        var request=HttpRequest.newBuilder(target.build().encode().toUri())
                .header("Accept","application/json").timeout(Duration.ofSeconds(20));
        if (authenticated) request.header("Authorization","Bearer "+key.strip());
        long reservation=authenticated ? quota.reserve(refreshing,reservedRows) : 0;
        try {
            var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try (var body=response.body()) {
                var headers=response.headers();
                Integer remaining=number(headers,"X-Quota-Remaining");
                Integer rowRemaining=number(headers,"X-Rows-Remaining");
                Instant reset=headerTime(headers.firstValue("X-Quota-Reset").orElse(null));
                if (response.statusCode()==429) {
                    Instant retry=headerTime(headers.firstValue("Retry-After").orElse(null));
                    quota.record(remaining,rowRemaining,retry==null ? Instant.now().plusSeconds(3600) : retry,reset);
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Loyalty Hub returned a quota or rate limit");
                }
                if (response.statusCode()!=200) {
                    if (authenticated && (response.statusCode()==401 || response.statusCode()==403))
                        quota.record(remaining,rowRemaining,Instant.now().plusSeconds(86400),reset);
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Loyalty Hub returned HTTP "+response.statusCode());
                }
                var bytes=body.readNBytes(512_001);
                if (bytes.length>512_000) throw invalidResponse();
                var responseJson=json.readTree(bytes);
                if (responseJson==null || !responseJson.isObject() || !responseJson.has("data")) throw invalidResponse();
                if (authenticated) {
                    var data=responseJson.path("data");
                    var metadata=responseJson.path("meta");
                    Integer headerLimit=number(headers,"X-Quota-Limit");
                    int confirmedLimit=headerLimit==null ? metadata.path("quota").asInt(-1) : headerLimit;
                    Integer rowLimit=number(headers,"X-Rows-Limit");
                    if (confirmedLimit<1 || confirmedLimit>100 || (rowLimit!=null && rowLimit>1000)) {
                        quota.record(remaining,rowRemaining,Instant.now().plusSeconds(86400),reset);
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Loyalty Hub did not confirm a free-tier quota");
                    }
                    if (remaining==null && metadata.path("used").canConvertToInt())
                        remaining=Math.max(0,confirmedLimit-metadata.path("used").asInt());
                    quota.record(remaining,rowRemaining,null,reset);
                    int rows=data.isArray() ? data.size() : data.path("offers").size();
                    if (rows>reservedRows) throw invalidResponse();
                    quota.delivered(reservation,rows);
                }
                return responseJson;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Loyalty Hub request was interrupted");
        } catch (IOException ex) {
            throw invalidResponse();
        }
    }

    private int limit() { return Math.max(1,Math.min(10,pageSize)); }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Loyalty Hub is unavailable or returned invalid data");
    }

    private static Integer number(HttpHeaders headers,String name) {
        try { return headers.firstValue(name).map(Integer::valueOf).orElse(null); }
        catch (NumberFormatException ex) { return null; }
    }

    private static Instant headerTime(String value) {
        if (value==null) return null;
        try {
            long seconds=Long.parseLong(value);
            if (seconds<0) return null;
            return seconds>=1_000_000_000 ? Instant.ofEpochSecond(seconds) : Instant.now().plusSeconds(Math.min(seconds,604800));
        } catch (NumberFormatException ex) {
            try { return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (java.time.DateTimeException ignored) { return instant(value); }
        }
    }
}
