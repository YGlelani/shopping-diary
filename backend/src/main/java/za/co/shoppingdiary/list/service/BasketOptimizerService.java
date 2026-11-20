package za.co.shoppingdiary.list.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.shoppingdiary.list.dto.*;
import za.co.shoppingdiary.list.entity.ShoppingListItem;
import za.co.shoppingdiary.list.repository.ShoppingListItemRepository;
import za.co.shoppingdiary.list.repository.ShoppingListRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BasketOptimizerService {

    private static final int MAX_SPLIT_STORES = 3;

    private final ShoppingListRepository lists;
    private final ShoppingListItemRepository items;
    private final NamedParameterJdbcTemplate jdbc;

    public OptimizeResponse optimize(Long userId, Long listId, OptimizeRequest req) {
        // 1. Ownership check
        lists.findByIdAndUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("List not found"));

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

        // 3. Load the (item × store) price matrix and active promotions
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
                       po.shelf_price, po.promo_price,
                       po.observed_at
                FROM price_observations po
                JOIN nearby_stores ns ON ns.id = po.store_id
                WHERE po.product_id IN (
                    SELECT product_id FROM shopping_list_items WHERE list_id = :listId
                )
                ORDER BY po.product_id, po.store_id, po.observed_at DESC, po.id DESC
            ),
            active_promos AS (
                SELECT pr.product_id, pr.store_id, pr.promo_price, CASE WHEN pr.promo_type = 'DISCOUNT' THEN 1 ELSE pr.min_quantity END AS min_quantity
                FROM promotions pr
                WHERE pr.status = 'ACTIVE' AND pr.promo_type IN ('MULTIBUY', 'DISCOUNT')
                  AND CURRENT_DATE BETWEEN pr.start_date AND pr.end_date
            )
            SELECT ns.id AS store_id, ns.name AS store_name,
                   ns.retailer_name, ns.distance_km,
                   li.id AS item_id, li.product_id, li.quantity,
                   p.canonical_name AS product_name,
                   lp.shelf_price, lp.promo_price AS regular_promo,
                   ap.promo_price AS multi_buy_promo, ap.min_quantity
            FROM nearby_stores ns
            CROSS JOIN shopping_list_items li
            LEFT JOIN latest_price lp
                   ON lp.store_id = ns.id AND lp.product_id = li.product_id
            LEFT JOIN active_promos ap
                   ON ap.store_id = ns.id AND ap.product_id = li.product_id
            LEFT JOIN products p ON p.id = li.product_id
            WHERE li.list_id = :listId AND li.product_id IS NOT NULL
            """;

        var params = new MapSqlParameterSource()
                .addValue("listId", listId)
                .addValue("lat", req.getLat())
                .addValue("lon", req.getLon())
                .addValue("radiusMeters", safeRadiusKm * 1000);

        jdbc.query(sql, params, rs -> {
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

                // Attach the full price data (including promos) to the store
                BigDecimal shelf = rs.getBigDecimal("shelf_price");
                if (shelf != null) {
                    store.pricesByProduct.computeIfAbsent(item.productId, ignored -> new ArrayList<>()).add(new PriceData(
                        shelf,
                        rs.getBigDecimal("regular_promo"),
                        rs.getBigDecimal("multi_buy_promo"),
                        rs.getObject("min_quantity") != null ? rs.getInt("min_quantity") : null
                    ));
                }
            }
            return null;
        });

        List<StoreCandidate> candidates = storesById.values().stream()
                .filter(store -> !store.pricesByProduct.isEmpty())
                .sorted(Comparator.comparingLong(store -> store.id))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No stores with prices for these items within " + safeRadiusKm + " km");
        }

        Comparator<OptimizationPlan> rank = Comparator.comparingInt(OptimizationPlan::getMissingItems)
                .thenComparing(OptimizationPlan::getGrandTotal)
                .thenComparingInt(plan -> plan.getStores().size());
        Map<Integer, OptimizationPlan> best = new HashMap<>();
        evaluateSubsets(candidates, 0, new ArrayList<>(), itemsById.values(), safeCostPerKm, best, rank);
        OptimizationPlan recommendation = best.values().stream().min(rank).orElseThrow();
        best.values().removeIf(plan -> plan.getMissingItems() != recommendation.getMissingItems());
        OptimizationPlan best1 = best.get(1);
        OptimizationPlan best2 = best.get(2);
        OptimizationPlan best3 = best.get(3);

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

    private void evaluateSubsets(List<StoreCandidate> candidates, int start, List<StoreCandidate> subset,
                                 Collection<ItemCandidate> items, BigDecimal costPerKm,
                                 Map<Integer, OptimizationPlan> best, Comparator<OptimizationPlan> rank) {
        for (int i = start; i < candidates.size(); i++) {
            subset.add(candidates.get(i));
            OptimizationPlan plan = buildPlan(subset, items, costPerKm);
            best.merge(plan.getStores().size(), plan,
                    (previous, current) -> rank.compare(previous, current) <= 0 ? previous : current);
            if (subset.size() < MAX_SPLIT_STORES) {
                evaluateSubsets(candidates, i + 1, subset, items, costPerKm, best, rank);
            }
            subset.remove(subset.size() - 1);
        }
    }

    static BigDecimal lineCost(PriceData price, BigDecimal quantity) {
        BigDecimal unitPrice = price.regularPromo() == null
                ? price.shelfPrice() : price.shelfPrice().min(price.regularPromo());
        BigDecimal cost = unitPrice.multiply(quantity);
        if (price.multiBuyPromo() != null && price.minQuantity() != null && price.minQuantity() > 0) {
            BigDecimal[] bundle = quantity.divideAndRemainder(BigDecimal.valueOf(price.minQuantity()));
            cost = cost.min(price.multiBuyPromo().multiply(bundle[0]).add(unitPrice.multiply(bundle[1])));
        }
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    private OptimizationPlan buildPlan(List<StoreCandidate> subset,
                                       Collection<ItemCandidate> allItems,
                                       BigDecimal costPerKm) {
        Map<Long, Long> itemToStore = new HashMap<>();
        Map<Long, BigDecimal> itemToTotal = new HashMap<>();
        BigDecimal productCost = BigDecimal.ZERO;
        int missing = 0;

        // Assign each item to cheapest store in subset
        for (ItemCandidate item : allItems) {
            Long bestStore = null;
            BigDecimal bestTotalCost = null;

            for (StoreCandidate store : subset) {
                List<PriceData> prices = store.pricesByProduct.get(item.productId);
                if (prices == null) continue;
                BigDecimal totalCost = prices.stream().map(price -> lineCost(price, item.quantity))
                        .min(BigDecimal::compareTo).orElseThrow();
                if (bestTotalCost == null || totalCost.compareTo(bestTotalCost) < 0) {
                    bestTotalCost = totalCost;
                    bestStore = store.id;
                }
            }
            if (bestStore == null) {
                missing++;
            } else {
                itemToStore.put(item.itemId, bestStore);
                itemToTotal.put(item.itemId, bestTotalCost);
                productCost = productCost.add(bestTotalCost);
            }
        }

        // Per-store subtotals (only used stores)
        Map<Long, BigDecimal> subtotals = new HashMap<>();
        for (ItemCandidate item : allItems) {
            Long storeId = itemToStore.get(item.itemId);
            if (storeId != null) {
                BigDecimal line = itemToTotal.get(item.itemId);
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
            BigDecimal total = itemToTotal.get(item.itemId);
            BigDecimal price = total.divide(item.quantity, 2, RoundingMode.HALF_UP);
            planItems.add(new PlanItem(
                    item.productId, item.productName, item.quantity,
                    storeId, storeNames.get(storeId),
                    price, total));
        }

        BigDecimal grand = productCost.add(transportTotal);
        return new OptimizationPlan(planStores, planItems,
                productCost, transportTotal, grand, missing);
    }

    // ---- inner types ----

    record PriceData(BigDecimal shelfPrice, BigDecimal regularPromo, BigDecimal multiBuyPromo, Integer minQuantity) {}

    static class StoreCandidate {
        Long id;
        String name;
        String retailerName;
        double distanceKm;
        Map<Long, List<PriceData>> pricesByProduct;
    }

    static class ItemCandidate {
        Long itemId;
        Long productId;
        String productName;
        BigDecimal quantity;
    }
}