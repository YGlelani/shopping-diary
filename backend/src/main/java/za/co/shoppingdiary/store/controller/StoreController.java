package za.co.shoppingdiary.store.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
public class StoreController {
    private final NamedParameterJdbcTemplate jdbc;

    @GetMapping("/nearby")
    public List<NearbyStore> nearby(@RequestParam double lat, @RequestParam double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new IllegalArgumentException("Invalid location");
        }

        return jdbc.query("""
            WITH origin AS (
                SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS point
            )
            SELECT s.id, s.name, r.name AS retailer, ST_Y(s.location) AS lat,
                   ST_X(s.location) AS lon, ST_Distance(s.location::geography, o.point) / 1000 AS distance_km
            FROM stores s
            JOIN retailers r ON r.id = s.retailer_id
            CROSS JOIN origin o
            WHERE s.active AND ST_DWithin(s.location::geography, o.point, 15000)
            ORDER BY distance_km, s.id
            """, Map.of("lat", lat, "lon", lon), (rs, row) -> new NearbyStore(
                rs.getLong("id"), rs.getString("name"), rs.getString("retailer"),
                rs.getDouble("lat"), rs.getDouble("lon"), rs.getDouble("distance_km")));
    }

    public record NearbyStore(long id, String name, String retailer, double lat, double lon, double distanceKm) {}
}
