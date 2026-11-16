package za.co.shoppingdiary.list;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.shoppingdiary.list.dto.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BasketOptimizerService {

    private static final int MAX_CANDIDATE_STORES = 7;
    private static final int MAX_SPLIT_STORES = 3;

    private final ShoppingListRepository lists;
    private final ShoppingListItemRepository items;
    private final NamedParameterJdbcTemplate jdbc;

    public OptimizeResponse optimize(Long userId, Long listId, OptimizeRequest req) {
        // 1. Ownership check
        lists.findByIdAndUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("List not found"));

        // Safely handle request parameters (prevents nulls if UI leaves fields blank)
        double safeRadiusKm = req.getRadiusKm() != null ? req.getRadiusKm() : 15.0;
        BigDecimal safeCostPerKm = req.getCostPerKm() != null ? req.getCostPerKm() : new BigDecimal("4.50");

        // 2. Load items
        List<ShoppingListItem> allItems = items.findByListIdOrderByIdAsc(listId);
        List<ShoppingListItem> pricedItems = allItems.stream()
                .filter(i -> i.getProductId() != null)
                .toList();
        int skipped = allItems.size() - pricedItems.size();

        if (pricedItems.isEmpty()) {
            throw new IllegalArgumentException("List has no resolvable items to optimize");
        }

        // 3. Load the (item × store) price matrix
        Map<Long, StoreCandidate> storesById = new LinkedHashMap<>();
        Map<Long, ItemCandidate> itemsById = new LinkedHashMap<>();

        String sql = """
            WITH user_point AS (
                SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS g
            ),
            nearby_stores AS (
                SELECT s.id, s.name, r.name AS retailer_name,
                       ST_Distance(s.location::geography, up.g) / 1000.0 AS distance_km
                FROM stores s
                CROSS JOIN user_point up
                JOIN retailers r ON r.id = s.retailer_id
                WHERE s.active
                  AND ST_DWithin(s.location::geography, up.g, :radiusMeters)
            ),
            latest_price AS (
                SELECT DISTINCT ON (po.product_id, po.store_id)
                       po.product_id, po.store_id,
                       COALESCE(po.promo_price, po.shelf_price) AS effective_price
                FROM price_observations po
                ORDER BY po.product_id, po.store_id, po.observed_at DESC
            )
            SELECT ns.id AS store_id, ns.name AS store_name,
                   ns.retailer_name, ns.distance_km,
                   li.id AS item_id, li.product_id, li.quantity,
                   p.canonical_name AS product_name,
                   lp.effective_price
            FROM nearby_stores ns
            CROSS JOIN shopping_list_items li
            LEFT JOIN latest_price lp
                   ON lp.store_id = ns.id AND lp.product_id = li.product_id
            LEFT JOIN products p ON p.id = li.product_id
            WHERE li.list_id = :listId AND li.product_id IS NOT NULL
            """;

        var params = new MapSqlParameterSource()
                .addValue("listId", listId)
                .addValue("lat", req.getLat())
                .addValue("lon", req.getLon())
                .addValue("radiusMeters", safeRadiusKm * 1000);

        jdbc.query(sql, params, rs -> {
            // THE FIX: Explicitly loop through the ResultSet
            while (rs.next()) {
                long storeId = rs.getLong("store_id");
                long itemId = rs.getLong("item_id");

                StoreCandidate store = storesById.computeIfAbsent(storeId, id -> {
                    StoreCandidate s = new StoreCandidate();
                    s.id = id;
                    try {
                        s.name = rs.getString("store_name");
                        s.retailerName = rs.getString("retailer_name");
                        s.distanceKm = rs.getDouble("distance_km");
                    } catch (SQLException e) { throw new RuntimeException(e); }
                    s.pricesByProduct = new HashMap<>();
                    return s;
                });

                ItemCandidate item = itemsById.computeIfAbsent(itemId, id -> {
                    ItemCandidate i = new ItemCandidate();
                    i.itemId = id;
                    try {
                        i.productId = rs.getLong("product_id");
                        i.productName = rs.getString("product_name");
                        i.quantity = rs.getBigDecimal("quantity");
                        if (i.quantity == null) i.quantity = BigDecimal.ONE;
                    } catch (SQLException e) { throw new RuntimeException(e); }
                    return i;
                });

                BigDecimal price = rs.getBigDecimal("effective_price");
                if (price != null) store.pricesByProduct.put(item.productId, price);
            }
            return null;
        });

        // 4. Filter to stores with at least one price, then rank and cap at 7
        List<StoreCandidate> candidates = storesById.values().stream()
                .filter(s -> !s.pricesByProduct.isEmpty())
                .sorted((a, b) -> {
                    int covA = a.pricesByProduct.size();
                    int covB = b.pricesByProduct.size();
                    if (covA != covB) return Integer.compare(covB, covA);
                    return Double.compare(a.distanceKm, b.distanceKm);
                })
                .limit(MAX_CANDIDATE_STORES)
                .toList();

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No stores with prices for these items within " + safeRadiusKm + " km");
        }

        // 5. Enumerate subsets of size 1..MAX_SPLIT_STORES
        List<OptimizationPlan> allPlans = new ArrayList<>();
        int n = candidates.size();
        for (int mask = 1; mask < (1 << n); mask++) {
            int bitCount = Integer.bitCount(mask);
            if (bitCount > MAX_SPLIT_STORES) continue;
            List<StoreCandidate> subset = new ArrayList<>(bitCount);
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) subset.add(candidates.get(i));
            }
            allPlans.add(buildPlan(subset, itemsById.values(), safeCostPerKm));
        }

        // 6. Filter to plans with best coverage, then rank by grand total
        int minMissing = allPlans.stream().mapToInt(OptimizationPlan::getMissingItems).min().orElse(0);
        List<OptimizationPlan> eligible = allPlans.stream()
                .filter(p -> p.getMissingItems() == minMissing)
                .filter(p -> !p.getStores().isEmpty())
                .toList();

        if (eligible.isEmpty()) {
            throw new IllegalArgumentException("Could not generate any valid shopping plans for these items.");
        }

        Comparator<OptimizationPlan> byTotal = Comparator.comparing(OptimizationPlan::getGrandTotal);

        OptimizationPlan recommendation = eligible.stream().min(byTotal).orElseThrow();
        OptimizationPlan best1 = eligible.stream()
                .filter(p -> p.getStores().size() == 1).min(byTotal).orElse(null);
        OptimizationPlan best2 = eligible.stream()
                .filter(p -> p.getStores().size() == 2).min(byTotal).orElse(null);
        OptimizationPlan best3 = eligible.stream()
                .filter(p -> p.getStores().size() == 3).min(byTotal).orElse(null);

        // 7. Savings vs single-store baseline
        BigDecimal savings = BigDecimal.ZERO;
        BigDecimal savingsPct = BigDecimal.ZERO;
        if (best1 != null && best1.getGrandTotal().compareTo(BigDecimal.ZERO) > 0) {
            savings = best1.getGrandTotal().subtract(recommendation.getGrandTotal())
                    .max(BigDecimal.ZERO);
            savingsPct = savings.multiply(new BigDecimal("100"))
                    .divide(best1.getGrandTotal(), 1, RoundingMode.HALF_UP);
        }

        return new OptimizeResponse(recommendation, best1, best2, best3,
                savings, savingsPct, safeCostPerKm.doubleValue(), safeRadiusKm,
                pricedItems.size(), skipped);
    }

    private OptimizationPlan buildPlan(List<StoreCandidate> subset,
                                       Collection<ItemCandidate> allItems,
                                       BigDecimal costPerKm) {
        Map<Long, Long> itemToStore = new HashMap<>();
        Map<Long, BigDecimal> itemToPrice = new HashMap<>();
        BigDecimal productCost = BigDecimal.ZERO;
        int missing = 0;

        // Assign each item to cheapest store in subset
        for (ItemCandidate item : allItems) {
            Long bestStore = null;
            BigDecimal bestPrice = null;
            for (StoreCandidate store : subset) {
                BigDecimal p = store.pricesByProduct.get(item.productId);
                if (p != null && (bestPrice == null || p.compareTo(bestPrice) < 0)) {
                    bestPrice = p;
                    bestStore = store.id;
                }
            }
            if (bestStore == null) {
                missing++;
            } else {
                itemToStore.put(item.itemId, bestStore);
                itemToPrice.put(item.itemId, bestPrice);
                productCost = productCost.add(item.quantity.multiply(bestPrice));
            }
        }

        // Per-store subtotals (only used stores)
        Map<Long, BigDecimal> subtotals = new HashMap<>();
        for (ItemCandidate item : allItems) {
            Long storeId = itemToStore.get(item.itemId);
            if (storeId != null) {
                BigDecimal line = item.quantity.multiply(itemToPrice.get(item.itemId));
                subtotals.merge(storeId, line, BigDecimal::add);
            }
        }

        // Transport per actually-used store
        List<PlanStore> planStores = new ArrayList<>();
        BigDecimal transportTotal = BigDecimal.ZERO;
        for (StoreCandidate store : subset) {
            BigDecimal storeSubtotal = subtotals.get(store.id);
            if (storeSubtotal == null) continue;
            BigDecimal storeTransport = BigDecimal.valueOf(2.0 * store.distanceKm)
                    .multiply(costPerKm)
                    .setScale(2, RoundingMode.HALF_UP);
            transportTotal = transportTotal.add(storeTransport);
            planStores.add(new PlanStore(
                    store.id, store.name, store.retailerName,
                    Math.round(store.distanceKm * 100.0) / 100.0,
                    storeTransport, storeSubtotal));
        }
        planStores.sort(Comparator.comparing(PlanStore::getDistanceKm));

        // Per-item breakdown
        List<PlanItem> planItems = new ArrayList<>();
        Map<Long, String> storeNames = new HashMap<>();
        planStores.forEach(ps -> storeNames.put(ps.getStoreId(), ps.getStoreName()));
        for (ItemCandidate item : allItems) {
            Long storeId = itemToStore.get(item.itemId);
            if (storeId == null) continue;
            BigDecimal price = itemToPrice.get(item.itemId);
            planItems.add(new PlanItem(
                    item.productId, item.productName, item.quantity,
                    storeId, storeNames.get(storeId),
                    price, item.quantity.multiply(price)));
        }

        BigDecimal grand = productCost.add(transportTotal);
        return new OptimizationPlan(planStores, planItems,
                productCost, transportTotal, grand, missing);
    }

    // ---- inner types ----

    static class StoreCandidate {
        Long id;
        String name;
        String retailerName;
        double distanceKm;
        Map<Long, BigDecimal> pricesByProduct;
    }

    static class ItemCandidate {
        Long itemId;
        Long productId;
        String productName;
        BigDecimal quantity;
    }
}