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
    public List<NearbyStore> nearby(@RequestParam double lat, @RequestParam double lon,
                                    @RequestParam(defaultValue = "15") double r) {
        if (!Double.isFinite(r) || r <= 0 || r > 100 || !Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
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
            WHERE s.active AND r.active AND s.store_code IS DISTINCT FROM 'ONLINE'
              AND ST_DWithin(s.location::geography, o.point, :radius)
            ORDER BY distance_km, s.id LIMIT 100
            """, Map.of("lat", lat, "lon", lon, "radius", r * 1000), (rs, row) -> new NearbyStore(
                rs.getLong("id"), rs.getString("name"), rs.getString("retailer"),
                rs.getDouble("lat"), rs.getDouble("lon"), rs.getDouble("distance_km")));
    }

    @GetMapping("/areas")
    public List<Area> areas(@RequestParam String q) {
        if (q.isBlank() || q.length() < 2 || q.length() > 100) return List.of();
        return jdbc.query("""
            SELECT DISTINCT ON (lower(s.city), lower(coalesce(s.province, '')))
                   s.city, s.province, ST_Y(s.location) AS lat, ST_X(s.location) AS lon
            FROM stores s JOIN retailers r ON r.id = s.retailer_id
            WHERE s.active AND r.active AND s.store_code IS DISTINCT FROM 'ONLINE'
              AND strpos(lower(s.city), lower(:q)) > 0
            ORDER BY lower(s.city), lower(coalesce(s.province, '')), s.id
            LIMIT 20
            """, Map.of("q", q.strip()), (rs, row) -> new Area(rs.getString("city"),
                    rs.getString("province"), rs.getDouble("lat"), rs.getDouble("lon")));
    }

    public record Area(String city, String province, double lat, double lon) {}
    public record NearbyStore(long id, String name, String retailer, double lat, double lon, double distanceKm) {}
}
