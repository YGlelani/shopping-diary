package za.co.shoppingdiary.product;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.product.dto.ProductSearchHit;
import za.co.shoppingdiary.product.dto.StorePriceRow;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductSearchService service;

    @GetMapping("/search")
    public List<ProductSearchHit> search(
            @RequestParam("q") String q,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "r", defaultValue = "15") double radiusKm) {

        if (q == null || q.trim().length() < 2) {
            throw new IllegalArgumentException("Query must be at least 2 characters");
        }
        if (radiusKm <= 0 || radiusKm > 100) {
            throw new IllegalArgumentException("radiusKm must be between 0 and 100");
        }
        return service.search(q.trim(), lat, lon, radiusKm);
    }

    @GetMapping("/{id}/prices")
    public List<StorePriceRow> prices(
            @PathVariable("id") long id,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "r", defaultValue = "15") double radiusKm) {

        if (radiusKm <= 0 || radiusKm > 100) {
            throw new IllegalArgumentException("radiusKm must be between 0 and 100");
        }
        return service.pricesForProduct(id, lat, lon, radiusKm);
    }
}