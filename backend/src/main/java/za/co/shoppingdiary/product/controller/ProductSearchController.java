package za.co.shoppingdiary.product.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import za.co.shoppingdiary.product.dto.ProductSearchHit;
import za.co.shoppingdiary.product.dto.StorePriceRow;
import za.co.shoppingdiary.product.service.ProductSearchService;

import java.util.List;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductSearchService service;

    @GetMapping("/categories")
    public List<String> categories() {
        return service.categories();
    }

    @GetMapping("/search")
    public List<ProductSearchHit> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(name = "r", defaultValue = "15") double radiusKm,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) Boolean priced,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        validateLocation(lat, lon, radiusKm);
        q = q.strip();
        if (q.length() > 120 || (!q.isEmpty() && (q.length() < 2
                || !q.codePoints().anyMatch(Character::isLetterOrDigit)))) {
            throw new IllegalArgumentException("Enter a search of 2 to 120 characters");
        }
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Invalid catalogue page");
        }
        if (priced != null && lat == null) {
            throw new IllegalArgumentException("Price availability requires a shopping location");
        }
        size = filter(size);
        if (size != null) {
            var terms = ProductSearchService.SearchTerms.parse(size);
            if (terms.size() == null || !terms.text().isEmpty()) {
                throw new IllegalArgumentException("Enter a package size such as 500 g, 2 L or 6 pack");
            }
            size = terms.size();
        }
        return service.search(q, lat, lon, radiusKm, filter(category), filter(brand),
                size, priced, offset, limit);
    }

    @GetMapping("/{id}/prices")
    public List<StorePriceRow> prices(
            @PathVariable("id") long id,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(name = "r", defaultValue = "15") double radiusKm,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(required = false) String retailer,
            @RequestParam(required = false) String verification,
            @RequestParam(required = false) Boolean promotion,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "price") String sort,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        validateLocation(lat, lon, radiusKm);
        if (quantity < 1 || quantity > 100 || limit < 1 || limit > 100 || offset < 0 || offset > 10000
                || (minPrice != null && minPrice.signum() < 0)
                || (maxPrice != null && (maxPrice.signum() <= 0
                    || (minPrice != null && minPrice.compareTo(maxPrice) > 0)))
                || !List.of("price", "distance", "freshness").contains(sort)
                || (verification != null && !List.of("VERIFIED", "UNVERIFIED").contains(verification))) {
            throw new IllegalArgumentException("Invalid comparison filters");
        }
        return service.pricesForProduct(id, lat, lon, radiusKm, BigDecimal.valueOf(quantity),
                filter(retailer), verification, promotion, minPrice, maxPrice, sort, offset, limit);
    }

    private static String filter(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 100) throw new IllegalArgumentException("Filter is too long");
        return value.strip();
    }

    private static void validateLocation(Double lat, Double lon, double radiusKm) {
        if (!Double.isFinite(radiusKm) || radiusKm <= 0 || radiusKm > 100) {
            throw new IllegalArgumentException("Radius must be greater than 0 and at most 100 km");
        }
        if ((lat == null) != (lon == null)
                || (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || Math.abs(lat) > 90 || Math.abs(lon) > 180))) {
            throw new IllegalArgumentException("Provide valid latitude and longitude together");
        }
    }

    @GetMapping("/{id}/history")
    public List<za.co.shoppingdiary.product.dto.PriceHistoryRow> getPriceHistory(@PathVariable("id") long id) {
        return service.priceHistory(id);
    }
}