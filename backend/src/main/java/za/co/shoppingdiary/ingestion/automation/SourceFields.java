package za.co.shoppingdiary.ingestion.automation;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;

final class SourceFields {
    private SourceFields() {}

    static String text(JsonNode node,String key) {
        var value=node.path(key);
        return value.isTextual() ? clean(value.asText(),1000) : null;
    }

    static String clean(String input,int max) {
        if (input==null) return null;
        var value=input.strip().replaceAll("[\\p{Cntrl}]", " ");
        return value.isBlank() ? null : value.substring(0,Math.min(value.length(),max));
    }

    static String url(String value) {
        if (value==null) return null;
        try {
            var uri=URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null ? value : null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    static Instant instant(String value) {
        try { return value==null ? null : OffsetDateTime.parse(value).toInstant(); }
        catch (java.time.DateTimeException ex) { return null; }
    }

    static BigDecimal price(JsonNode node,String key) {
        var value=node.path(key);
        if (!(value.isNumber() || value.isTextual())) return null;
        try {
            var amount=new BigDecimal(value.asText());
            return amount.signum()>0 && amount.compareTo(new BigDecimal("10000000000"))<0
                    && amount.stripTrailingZeros().scale()<=2 ? amount : null;
        } catch (NumberFormatException ex) { return null; }
    }

    static Boolean flag(JsonNode node,String key) {
        return node.path(key).isBoolean() ? node.path(key).booleanValue() : null;
    }
}
