package za.co.shoppingdiary.list.service;

import org.junit.jupiter.api.Test;
import za.co.shoppingdiary.list.dto.OptimizeRequest;
import za.co.shoppingdiary.price.OfferPricing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static za.co.shoppingdiary.list.service.BasketOptimizerService.*;

class BasketOptimizerServiceTest {
    private static final OffsetDateTime OBSERVED = OffsetDateTime.parse("2026-11-23T12:00:00+02:00");

    @Test
    void preservesFractionalRemainderAfterApplyingBundle() {
        assertEquals(amount("64.00"), OfferPricing.total(amount("28"),amount("2.5"),amount("50"),2));
    }

    @Test
    void preservesExactBundleTotalAcrossThreeItems() {
        assertEquals(amount("100.00"), OfferPricing.total(amount("40"),amount("3"),amount("100"),3));
    }

    @Test
    void keepsRegularDiscountWhenBundlesCostMore() {
        assertEquals(amount("40.00"), OfferPricing.total(amount("20"),amount("2"),amount("50"),2));
    }

    @Test
    void comparesCoverageBeforeCostAndChargesOneRoundTrip() {
        var entries = List.of(entry(1),entry(2),entry(3));
        var first = candidate(1,28.0,Map.of(1L,30,2L,55,3L,60));
        var second = candidate(2,28.02,Map.of(2L,25,3L,30));
        var third = candidate(3,28.04,Map.of(3L,20));
        var request = request();
        var unknown = search(List.of(first,second,third),entries,request,3);
        assertThat(unknown.get(3).grocery()).isEqualByComparingTo("75");
        assertThat(unknown.get(3).transport()).isNull();
        request.setTravelMode(OptimizeRequest.TravelMode.DRIVE);
        request.setCostPerKm(amount("10"));
        var priced = search(List.of(first,second,third),entries,request,3);
        assertThat(priced.get(1).assignments()).hasSize(3);
        assertThat(priced.get(2).knownCost()).isLessThan(priced.get(1).knownCost());
        assertThat(priced.get(3).knownCost()).isGreaterThan(priced.get(2).knownCost());
        assertThat(priced.get(3).roundTripKm()).isBetween(7.9,8.1);
        request.setCostPerKm(amount("40"));
        var expensive = search(List.of(first,second,third),entries,request,3);
        assertThat(expensive.get(1).knownCost()).isLessThan(expensive.get(2).knownCost());
    }

    @Test
    void boundedSearchKeepsUniqueCoverageAcrossLargerBaskets() {
        var entries = IntStream.rangeClosed(1,120).mapToObj(id -> entry(id)).toList();
        var regular = IntStream.rangeClosed(1,32).mapToObj(id ->
                candidate(id,28.001+id/1000.0,IntStream.rangeClosed(1,119)
                        .boxed().collect(java.util.stream.Collectors.toMap(Integer::longValue, ignored -> 10)))).toList();
        var candidates = new java.util.ArrayList<>(regular);
        candidates.add(candidate(100,28.05,Map.of(120L,11)));
        var request = request();
        var results = search(candidates,entries,request,3);
        assertThat(results.get(1).assignments()).hasSize(119);
        assertThat(results.get(2).assignments()).hasSize(120);
        assertThat(results.get(2).grocery()).isEqualByComparingTo("1201");
        assertThat(results.get(2).stores()).extracting(Branch::id).contains(100L);
    }

    private static OptimizeRequest request() {
        var request = new OptimizeRequest();
        request.setLat(-26.0);
        request.setLon(28.0);
        return request;
    }

    private static Entry entry(long id) {
        return new Entry(id,id,"Entry "+id,BigDecimal.ONE,"pack",null);
    }

    private static Candidate candidate(long id,double longitude,Map<Long,Integer> prices) {
        var branch = new Branch(id,id,"Branch "+id,"Retailer","",-26,longitude,0);
        var offers = new java.util.HashMap<Long,Offer>();
        prices.forEach((item,price) -> offers.put(item,new Offer(id,item,item,"Entry "+item,"1 kg",null,
                BigDecimal.valueOf(price),item,OBSERVED,"VERIFIED","MANUAL",null,"SHELF",false)));
        return new Candidate(branch,offers);
    }

    private static BigDecimal amount(String text) {
        return new BigDecimal(text);
    }
}
