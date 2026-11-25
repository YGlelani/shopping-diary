package za.co.shoppingdiary.ingestion.automation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface PriceDataSource {
    String name();
    boolean enabled();
    Page collect(String query, int offset, Instant changedSince);

    record Page(List<Price> rows,Integer nextOffset,Instant updatedAt) {}
    record Price(String providerId,String barcode,String sku,String name,String brand,String size,
                 String retailer,String scope,String scopeLabel,String osmType,Long osmId,
                 BigDecimal amount,BigDecimal regular,BigDecimal promotional,BigDecimal member,String currency,
                 Instant observedAt,LocalDate observedOn,String url,String terms,Boolean loyaltyRequired,Boolean inStock,
                 com.fasterxml.jackson.databind.JsonNode attributes) {
        public Price(String providerId,String barcode,String sku,String name,String brand,String size,
                     String retailer,String scope,String scopeLabel,String osmType,Long osmId,
                     BigDecimal amount,BigDecimal regular,BigDecimal promotional,BigDecimal member,String currency,
                     Instant observedAt,LocalDate observedOn,String url,String terms,Boolean loyaltyRequired,Boolean inStock) {
            this(providerId,barcode,sku,name,brand,size,retailer,scope,scopeLabel,osmType,osmId,amount,regular,
                    promotional,member,currency,observedAt,observedOn,url,terms,loyaltyRequired,inStock,null);
        }
    }
}
