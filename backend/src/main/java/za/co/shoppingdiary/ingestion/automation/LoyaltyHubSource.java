package za.co.shoppingdiary.ingestion.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

import static za.co.shoppingdiary.ingestion.automation.SourceFields.*;

@Component
@RequiredArgsConstructor
public class LoyaltyHubSource implements PriceDataSource {
    private final LoyaltyHubQuota quota;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Value("${LOYALTYHUB_FREE_TIER_ENABLED:false}") private boolean optedIn;
    @Value("${loyaltyhub.api.key:}") private String key;

    public String name() { return "LOYALTYHUB"; }
    public boolean enabled() { return optedIn && key!=null && !key.isBlank(); }

    @Override
    public Page collect(String query,int offset,Instant changedSince) {
        if (!enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"A confirmed free-tier Loyalty Hub account is required");
        var builder=UriComponentsBuilder.fromUriString("https://loyaltyhub.co.za/api/v1/prices")
                .queryParam("search",query).queryParam("limit",10).queryParam("offset",offset)
                .queryParam("max_age_days",7);
        if (changedSince!=null) builder.queryParam("changed_since",changedSince);
        var request=HttpRequest.newBuilder(builder.build().encode().toUri())
                .header("Authorization","Bearer "+key).header("Accept","application/json")
                .timeout(Duration.ofSeconds(20)).GET().build();
        quota.reserve();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
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
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Loyalty Hub returned HTTP "+response.statusCode());
                }
                var responseJson=json.readTree(body.readNBytes(512_000));
                if (responseJson==null) throw new IOException("Empty response");
                var metadata=responseJson.path("meta");
                Integer limit=number(headers,"X-Quota-Limit");
                int documentedLimit=limit==null ? metadata.path("quota").asInt(-1) : limit;
                if (documentedLimit<1 || documentedLimit>100) {
                    quota.record(remaining,rowRemaining,Instant.now().plusSeconds(86400),reset);
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Loyalty Hub did not confirm a free-tier quota");
                }
                if (remaining==null && metadata.path("used").canConvertToInt()) remaining=Math.max(0,documentedLimit-metadata.path("used").asInt());
                quota.record(remaining,rowRemaining,null,reset);
                var data=responseJson.path("data");
                if (!data.isArray() || data.size()>10) throw new IOException("Unexpected response size");
                var rows=new ArrayList<Price>();
                for (var row : data) {
                    String region=text(row,"region");
                    String basis=text(row,"price_basis");
                    String scope=region!=null ? "REGIONAL" : "online".equals(basis) ? "ONLINE"
                            : "single_store".equals(basis) ? "CATALOGUE" : "UNKNOWN";
                    var regular=price(row,"was_price");
                    var amount=price(row,"price");
                    Boolean reduced=flag(row,"on_promotion");
                    if (Boolean.FALSE.equals(reduced) && regular==null) regular=amount;
                    var promo=Boolean.TRUE.equals(reduced) && regular!=null && amount!=null && amount.compareTo(regular)<0 ? amount : null;
                    rows.add(new Price(null,text(row,"barcode"),text(row,"retailer_sku"),text(row,"name"),
                            text(row,"brand"),text(row,"unit_size"),text(row,"retailer"),scope,region==null ? basis : region,
                            null,null,amount,regular,promo,price(row,"loyalty_price"),text(row,"currency"),
                            instant(text(row,"last_seen_at")),null,url(text(row,"product_url")),
                            text(row,"promotion"),flag(row,"promotion_loyalty"),flag(row,"in_stock")));
                }
                var next=metadata.path("next_offset");
                boolean hasMore=metadata.path("has_more").asBoolean(false);
                if (hasMore && (!next.canConvertToInt() || next.asInt()<=offset)) throw new IOException("Invalid pagination");
                return new Page(rows,hasMore ? next.asInt() : null,instant(text(metadata,"updated_at")));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Loyalty Hub request was interrupted");
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Loyalty Hub is unavailable or returned invalid data");
        }
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
