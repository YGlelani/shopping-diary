
package za.co.shoppingdiary.list.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.shoppingdiary.list.dto.*;
import za.co.shoppingdiary.list.entity.ShoppingList;
import za.co.shoppingdiary.list.entity.ShoppingListItem;
import za.co.shoppingdiary.list.repository.ShoppingListItemRepository;
import za.co.shoppingdiary.list.repository.ShoppingListRepository;
import za.co.shoppingdiary.product.entity.Product;
import za.co.shoppingdiary.product.repository.ProductRepository;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import za.co.shoppingdiary.price.OfferPricing;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShoppingListService {

    private final ShoppingListRepository lists;
    private final ShoppingListItemRepository items;
    private final ProductRepository products;
    private final NamedParameterJdbcTemplate jdbc;
    private final ShoppingEstimateService estimates;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxPriceAgeDays;

    @Transactional
    public ListDetailResponse create(
            Long userId,
            CreateListRequest req, UUID requestId
    ) {
        var previous = replayCreation(userId, requestId);
        if (previous.isPresent()) return toDetail(previous.get());
        ShoppingList list = new ShoppingList();
        list.setUserId(userId);
        list.setCreationRequestId(requestId);
        list.setStatus("ACTIVE");
        applyMetadata(list, req);
        return toDetail(lists.saveAndFlush(list));
    }

    @Transactional(readOnly = true)
    public List<ListDetailResponse> mine(Long userId) {
        var owned = lists.findByUserIdOrderByCreatedAtDesc(userId);
        if (owned.isEmpty()) return List.of();
        var allItems = items.findByListIdInOrderByIdAsc(owned.stream().map(ShoppingList::getId).toList());
        var grouped = allItems.stream().collect(Collectors.groupingBy(ShoppingListItem::getListId));
        var catalogue = catalogue(allItems);
        return owned.stream().map(list -> toDetail(list, grouped.getOrDefault(list.getId(), List.of()), catalogue)).toList();
    }

    @Transactional(readOnly = true)
    public ListDetailResponse detail(Long userId, Long listId) {
        return toDetail(ownedList(userId, listId));
    }

    @Transactional
    public ListDetailResponse update(Long userId, Long listId, CreateListRequest req, long revision, String status) {
        ShoppingList list = locked(userId, listId, revision);
        applyMetadata(list, req);
        if (status != null) {
            if (!Set.of("ACTIVE", "ARCHIVED", "COMPLETED", "DRAFT").contains(status)) {
                throw new IllegalArgumentException("Invalid list status");
            }
            list.setStatus(status);
        }
        return changed(list);
    }

    @Transactional
    public ListDetailResponse duplicate(Long userId, Long listId, String name, UUID requestId) {
        if (requestId != null) ownedList(userId, listId);
        var previous = replayCreation(userId, requestId);
        if (previous.isPresent()) return toDetail(previous.get());
        ShoppingList original = locked(userId, listId, null);
        ShoppingList copy = new ShoppingList();
        copy.setUserId(userId);
        copy.setCreationRequestId(requestId);
        copy.setName(name.strip());
        copy.setDescription(original.getDescription());
        copy.setBudgetAmount(original.getBudgetAmount());
        copy.setLocationLabel(original.getLocationLabel());
        copy.setLocationLat(original.getLocationLat());
        copy.setLocationLon(original.getLocationLon());
        copy.setStatus("ACTIVE");
        lists.saveAndFlush(copy);
        jdbc.update("""
            INSERT INTO shopping_list_items (list_id, product_id, free_text, quantity, unit, preferred_brand,
                                             flexible_brand, notes, substitute_product_id)
            SELECT :copy, product_id, free_text, quantity, unit, preferred_brand, flexible_brand, notes, substitute_product_id
            FROM shopping_list_items WHERE list_id = :original ORDER BY id
            """, Map.of("copy", copy.getId(), "original", listId));
        return toDetail(copy);
    }

    @Transactional
    public ListDetailResponse.Item addItem(Long userId, Long listId, AddItemRequest req, UUID requestId) {
        ShoppingList list = locked(userId, listId, null);
        if (requestId != null) {
            var previous = jdbc.queryForList("""
                SELECT item_id FROM shopping_list_add_requests WHERE list_id = :list AND request_id = :request
                """, Map.of("list", listId, "request", requestId), Long.class);
            if (!previous.isEmpty()) {
                if (previous.getFirst() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This item was removed");
                var saved = item(listId, previous.getFirst());
                return toItem(saved, product(saved.getProductId()));
            }
        }
        requireEditable(list);
        Product selected = product(req.getProductId());
        ShoppingListItem entry = new ShoppingListItem();
        entry.setListId(listId);
        applyItem(entry, req, selected);
        if (entry.getProductId() != null && !entry.isPurchased()) {
            var match = items.findByListIdOrderByIdAsc(listId).stream()
                    .filter(existing -> sameEntry(existing, entry)).findFirst();
            if (match.isPresent()) {
                var existing = match.get();
                existing.setQuantity(quantity(existing.getQuantity().add(entry.getQuantity()), entry.getUnit()));
                return saveAdded(list, existing, selected, requestId);
            }
        }
        return saveAdded(list, entry, selected, requestId);
    }

    private ListDetailResponse.Item saveAdded(ShoppingList list, ShoppingListItem entry, Product product, UUID requestId) {
        items.saveAndFlush(entry);
        if (requestId != null) {
            jdbc.update("""
                INSERT INTO shopping_list_add_requests (list_id, request_id, item_id) VALUES (:list, :request, :item)
                """, Map.of("list", list.getId(), "request", requestId, "item", entry.getId()));
        }
        list.setUpdatedAt(Instant.now());
        return toItem(entry, product);
    }

    @Transactional
    public ListDetailResponse updateItem(Long userId, Long listId, Long itemId, AddItemRequest req, long revision) {
        ShoppingList list = locked(userId, listId, revision);
        requireEditable(list);
        var entry = item(listId, itemId);
        applyItem(entry, req, product(req.getProductId()));
        return changed(list);
    }

    @Transactional
    public void incrementItem(Long userId, Long listId, Long itemId) {
        ShoppingList list = locked(userId, listId, null);
        requireEditable(list);
        var item = item(listId, itemId);
        item.setQuantity(quantity(item.getQuantity().add(BigDecimal.ONE), item.getUnit()));
        list.setUpdatedAt(Instant.now());
    }

    @Transactional
    public void removeItem(Long userId, Long listId, Long itemId, Long revision) {
        ShoppingList list = locked(userId, listId, revision);
        requireEditable(list);
        items.delete(item(listId, itemId));
        list.setUpdatedAt(Instant.now());
    }

    @Transactional
    public ListDetailResponse clearPurchased(Long userId, Long listId, long revision) {
        ShoppingList list = locked(userId, listId, revision);
        requireEditable(list);
        jdbc.update("DELETE FROM shopping_list_items WHERE list_id = :id AND purchased", Map.of("id", listId));
        return changed(list);
    }

    @Transactional
    public void delete(Long userId, Long listId, Long revision) {
        lists.delete(locked(userId, listId, revision));
    }

    private static final Set<String> PACKAGE_UNITS = Set.of("item", "pack", "bottle", "carton", "bag");
    private static final Set<String> FRACTIONAL_UNITS = Set.of("kg", "g", "l", "ml");

    static BigDecimal quantity(BigDecimal value, String unit) {
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("10000000")) >= 0
                || value.stripTrailingZeros().scale() > 3
                || ((unit == null || !FRACTIONAL_UNITS.contains(unit)) && value.stripTrailingZeros().scale() > 0)) {
            throw new IllegalArgumentException("Use a positive quantity with up to three decimals for weight or volume, or a whole package count");
        }
        return value;
    }

    private void applyItem(ShoppingListItem entry, AddItemRequest req, Product selected) {
        String name = nullable(req.getCustomName());
        String unit = nullable(req.getUnit());
        unit = unit == null ? (selected == null ? "item" : "pack") : unit.toLowerCase(Locale.ROOT);
        if (!PACKAGE_UNITS.contains(unit) && !FRACTIONAL_UNITS.contains(unit) && !"dozen".equals(unit)) {
            throw new IllegalArgumentException("Unsupported purchase unit");
        }
        if (selected == null && name == null) throw new IllegalArgumentException("Enter a custom name or select a product");
        if (selected != null && !PACKAGE_UNITS.contains(unit)) {
            throw new IllegalArgumentException("Catalogue quantities count packages. Use a custom item for loose weight or volume");
        }
        boolean flexible = Boolean.TRUE.equals(req.getFlexibleBrand());
        var alternate = product(req.getSubstituteProductId());
        if (alternate != null && (!flexible || !comparable(selected, alternate))) {
            throw new IllegalArgumentException("Choose a different product in the same category with the same measured package size");
        }
        entry.setProductId(selected == null ? null : selected.getId());
        entry.setFreeText(selected == null ? name : selected.getCanonicalName());
        entry.setQuantity(quantity(req.getQuantity() == null ? BigDecimal.ONE : req.getQuantity(), unit));
        entry.setUnit(unit);
        entry.setPreferredBrand(nullable(req.getPreferredBrand()));
        entry.setFlexibleBrand(flexible);
        entry.setNotes(nullable(req.getNotes()));
        entry.setPurchased(Boolean.TRUE.equals(req.getPurchased()));
        entry.setSubstituteProductId(alternate == null ? null : alternate.getId());
    }

    private static boolean comparable(Product first, Product second) {
        if (first == null || first.getId().equals(second.getId())) return false;
        boolean sameCategory = first.getCategoryId() != null
                ? Objects.equals(first.getCategoryId(), second.getCategoryId())
                : second.getCategoryId() == null && first.getCategory() != null
                    && first.getCategory().equalsIgnoreCase(second.getCategory());
        var a = measure(first);
        var b = measure(second);
        return sameCategory && a != null && b != null && a.unit().equals(b.unit())
                && a.quantity().compareTo(b.quantity()) == 0;
    }

    private static OfferPricing.Measure measure(Product p) {
        return OfferPricing.measure(p.getBaseQuantity(), p.getBaseUnit(), p.getSizeText());
    }

    private static boolean sameEntry(ShoppingListItem first, ShoppingListItem second) {
        return !first.isPurchased() && Objects.equals(first.getProductId(), second.getProductId())
                && Objects.equals(first.getUnit() == null ? "pack" : first.getUnit(), second.getUnit())
                && Objects.equals(first.getPreferredBrand(), second.getPreferredBrand())
                && Objects.equals(first.getSubstituteProductId(), second.getSubstituteProductId())
                && first.isFlexibleBrand() == second.isFlexibleBrand() && Objects.equals(first.getNotes(), second.getNotes());
    }

    private Product product(Long id) {
        return id == null ? null : products.findById(id).orElseThrow(() -> notFound("Product not found"));
    }

    private ShoppingListItem item(Long listId, Long itemId) {
        return items.findByIdAndListId(itemId, listId).orElseThrow(() -> notFound("Item not found"));
    }

    private Optional<ShoppingList> replayCreation(Long userId, UUID requestId) {
        if (requestId == null) return Optional.empty();
        jdbc.queryForObject("SELECT id FROM users WHERE id = :id FOR UPDATE", Map.of("id", userId), Long.class);
        return lists.findByUserIdAndCreationRequestId(userId, requestId);
    }

    private ShoppingList locked(Long userId, Long id, Long revision) {
        var list = lists.lockOwned(id, userId).orElseThrow(() -> notFound("List not found"));
        if (revision != null && list.getRevision() != revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This list changed. Reload it before saving your edits.");
        }
        return list;
    }

    private static void requireEditable(ShoppingList list) {
        if ("ARCHIVED".equals(list.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Restore this list before changing its items");
        }
    }

    private ListDetailResponse changed(ShoppingList list) {
        list.setUpdatedAt(Instant.now());
        lists.flush();
        return toDetail(list);
    }

    private static void applyMetadata(ShoppingList list, CreateListRequest req) {
        var lat = req.getLocationLat();
        var lon = req.getLocationLon();
        if ((lat == null) != (lon == null) || (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180))) {
            throw new IllegalArgumentException("Invalid preferred shopping location");
        }
        list.setName(req.getName().strip());
        list.setDescription(nullable(req.getDescription()));
        list.setBudgetAmount(req.getBudgetAmount());
        list.setLocationLat(lat);
        list.setLocationLon(lon);
        list.setLocationLabel(lat == null ? null : nullable(req.getLocationLabel()));
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    @Transactional(readOnly = true)
    public List<BasketRow> baskets(
            Long userId,
            Long listId,
            double lat,
            double lon,
            double radiusKm
    ) {
        ownedList(userId, listId);

        if (!Double.isFinite(lat)
                || !Double.isFinite(lon)
                || lat < -90 || lat > 90
                || lon < -180 || lon > 180) {
            throw new IllegalArgumentException(
                    "Invalid coordinates"
            );
        }

        if (!Double.isFinite(radiusKm)
                || radiusKm <= 0
                || radiusKm > 100) {
            throw new IllegalArgumentException(
                    "Invalid search radius"
            );
        }

        if (maxPriceAgeDays <= 0) {
            throw new IllegalStateException(
                    "Invalid price freshness configuration"
            );
        }

        int totalItems =
                (int) items.countByListId(listId);

        if (totalItems == 0) {
            return List.of();
        }

        String sql = """
            WITH nearby_stores AS (
                SELECT
                    s.id,
                    s.name,
                    r.name AS retailer_name,
                    ST_Distance(
                        s.location::geography,
                        ST_SetSRID(
                            ST_MakePoint(:lon, :lat),
                            4326
                        )::geography
                    ) AS distance_m
                FROM stores s
                JOIN retailers r
                    ON r.id = s.retailer_id
                WHERE s.active = TRUE
                  AND r.active = TRUE
                  AND s.store_code IS DISTINCT FROM 'ONLINE'
                  AND ST_DWithin(
                      s.location::geography,
                      ST_SetSRID(
                          ST_MakePoint(:lon, :lat),
                          4326
                      )::geography,
                      :radiusMeters
                  )
            )
            SELECT
                ns.id AS store_id,
                ns.name AS store_name,
                ns.retailer_name,
                ns.distance_m,
                COUNT(line.total) AS covered_items,
                SUM(line.total) AS subtotal
            FROM nearby_stores ns
            CROSS JOIN shopping_list_items li
            LEFT JOIN LATERAL shopping_list_item_offer(li, ns.id, :maxAgeDays) line ON TRUE
            WHERE li.list_id = :listId
            GROUP BY
                ns.id,
                ns.name,
                ns.retailer_name,
                ns.distance_m
            HAVING COUNT(line.total) > 0
            ORDER BY
                covered_items DESC,
                subtotal ASC,
                distance_m ASC
            """;

        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("listId", listId)
                        .addValue("lat", lat)
                        .addValue("lon", lon)
                        .addValue(
                                "radiusMeters",
                                radiusKm * 1000.0
                        )
                        .addValue(
                                "maxAgeDays",
                                maxPriceAgeDays
                        );

        return jdbc.query(sql, params, (rs, rowNum) -> {
            BasketRow row = new BasketRow();

            row.setStoreId(rs.getLong("store_id"));
            row.setStoreName(
                    rs.getString("store_name")
            );
            row.setRetailerName(
                    rs.getString("retailer_name")
            );
            row.setDistanceMeters(
                    rs.getDouble("distance_m")
            );
            row.setSubtotal(
                    rs.getBigDecimal("subtotal")
            );

            int covered = rs.getInt("covered_items");

            row.setCoveredItems(covered);
            row.setTotalItems(totalItems);
            row.setMissingItems(totalItems - covered);

            row.setCoveragePct(
                    Math.round(
                            1000.0 * covered / totalItems
                    ) / 10.0
            );

            return row;
        });
    }

    public record ListCoverage(long listId, int pricedItems, boolean hasLocation) {}

    @Transactional(readOnly = true)
    public List<ListCoverage> coverage(Long userId, Double lat, Double lon, double radius) {
        if ((lat == null) != (lon == null) || (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180))
                || !Double.isFinite(radius) || radius <= 0 || radius > 100) {
            throw new IllegalArgumentException("Invalid shopping location or radius");
        }
        var params = new MapSqlParameterSource("owner", userId)
                .addValue("lat", lat, java.sql.Types.DOUBLE).addValue("lon", lon, java.sql.Types.DOUBLE)
                .addValue("radius", radius * 1000).addValue("age", maxPriceAgeDays);
        return jdbc.query("""
            WITH owned AS (
                SELECT id, ST_SetSRID(ST_MakePoint(coalesce(location_lon, :lon), coalesce(location_lat, :lat)),4326)::geography AS point
                FROM shopping_lists WHERE user_id = :owner
            ),
            nearby AS MATERIALIZED (
                SELECT l.id AS list_id, s.id AS store_id
                FROM owned l CROSS JOIN stores s JOIN retailers r ON r.id = s.retailer_id
                WHERE s.active AND r.active AND upper(s.store_code) IS DISTINCT FROM 'ONLINE'
                    AND ST_DWithin(s.location::geography, l.point, :radius)
            )
            SELECT l.id, l.point IS NOT NULL AS has_location,
                count(li.id) FILTER (WHERE EXISTS (
                    SELECT 1 FROM nearby s CROSS JOIN LATERAL shopping_list_item_offer(li, s.store_id, :age)
                    WHERE s.list_id = l.id
                )) AS priced
            FROM owned l LEFT JOIN shopping_list_items li ON li.list_id = l.id
            GROUP BY l.id, l.point
            """, params, (rs, row) -> new ListCoverage(rs.getLong("id"), rs.getInt("priced"), rs.getBoolean("has_location")));
    }

    public record ItemEstimate(long itemId, long storeId, String storeName, long productId, String productName,
                               BigDecimal total) {}

    @Transactional(readOnly = true)
    public List<ItemEstimate> estimate(Long userId, Long listId, double lat, double lon, double radius) {
        ownedList(userId, listId);
        return estimates.estimate(userId, List.of(listId), lat, lon, radius, null, false).stream()
                .filter(line -> line.total() != null)
                .map(line -> new ItemEstimate(line.itemId(), line.storeId(), line.storeName(),
                        line.productId(), line.productName(), line.total())).toList();
    }

    private ShoppingList ownedList(Long userId, Long listId) {
        return lists.findByIdAndUserId(listId, userId).orElseThrow(() -> notFound("List not found"));
    }

    private record Catalogue(Map<Long, Product> products, Map<Long, String> categories) {}

    private Catalogue catalogue(List<ShoppingListItem> entries) {
        var found = products.findAllById(entries.stream().map(ShoppingListItem::getProductId)
                .filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, product -> product));
        var ids = found.values().stream().map(Product::getCategoryId).filter(Objects::nonNull).distinct().toList();
        var categories = new HashMap<Long, String>();
        if (!ids.isEmpty()) {
            jdbc.query("SELECT id, name FROM categories WHERE id IN (:ids)", Map.of("ids", ids), rs -> {
                categories.put(rs.getLong("id"), rs.getString("name"));
            });
        }
        return new Catalogue(found, categories);
    }

    private ListDetailResponse toDetail(ShoppingList list) {
        var entries = items.findByListIdOrderByIdAsc(list.getId());
        return toDetail(list, entries, catalogue(entries));
    }

    private ListDetailResponse toDetail(ShoppingList list, List<ShoppingListItem> entries, Catalogue catalogue) {
        var response = new ListDetailResponse();
        response.setId(list.getId());
        response.setName(list.getName());
        response.setBudgetAmount(list.getBudgetAmount());
        response.setStatus(list.getStatus());
        response.setCreatedAt(list.getCreatedAt());
        response.setUpdatedAt(list.getUpdatedAt());
        response.setRevision(list.getRevision());
        response.setDescription(list.getDescription());
        response.setLocationLabel(list.getLocationLabel());
        response.setLocationLat(list.getLocationLat());
        response.setLocationLon(list.getLocationLon());
        response.setItems(entries.stream().map(item -> {
            var product = catalogue.products().get(item.getProductId());
            var row = toItem(item, product);
            if (product != null && catalogue.categories().containsKey(product.getCategoryId())) {
                row.setCategory(catalogue.categories().get(product.getCategoryId()));
            }
            return row;
        }).toList());
        return response;
    }

    private ListDetailResponse.Item toItem(ShoppingListItem item, Product product) {
        var response = new ListDetailResponse.Item();
        response.setId(item.getId());
        response.setProductId(item.getProductId());
        response.setProductName(product == null ? item.getFreeText() : product.getCanonicalName());
        response.setCustomName(product == null ? item.getFreeText() : null);
        response.setBrand(product == null ? item.getPreferredBrand() : product.getBrand());
        response.setPreferredBrand(item.getPreferredBrand());
        response.setNotes(item.getNotes());
        response.setFlexibleBrand(item.isFlexibleBrand());
        response.setPurchased(item.isPurchased());
        response.setSubstituteProductId(item.getSubstituteProductId());
        response.setQuantity(item.getQuantity());
        response.setUnit(item.getUnit());
        if (product != null) {
            response.setSizeText(product.getSizeText());
            response.setCategory(product.getCategory());
            response.setBarcode(product.getBarcode());
            response.setImageUrl(product.getImageUrl());
            var measure = measure(product);
            if (measure != null && (item.getUnit() == null || PACKAGE_UNITS.contains(item.getUnit()))) {
                response.setCombinedMeasure(measure.quantity().multiply(item.getQuantity()).stripTrailingZeros());
                response.setMeasureUnit(measure.unit());
            }
        }
        return response;
    }
}
