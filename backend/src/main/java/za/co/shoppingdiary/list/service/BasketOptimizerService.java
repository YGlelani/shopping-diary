
package za.co.shoppingdiary.list.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
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
    private static final double DEFAULT_RADIUS_KM = 15.0;
    private static final BigDecimal DEFAULT_COST_PER_KM =
            new BigDecimal("4.50");

    private final ShoppingListRepository lists;
    private final ShoppingListItemRepository items;
    private final NamedParameterJdbcTemplate jdbc;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxPriceAgeDays;

    @Transactional(readOnly = true)
    public OptimizeResponse optimize(
            Long userId,
            Long listId,
            OptimizeRequest req
    ) {
        if (userId == null || listId == null || req == null) {
            throw new IllegalArgumentException(
                    "User, list and request are required"
            );
        }

        lists.findByIdAndUserId(listId, userId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "List not found"));

        if (req.getLat() == null || req.getLon() == null) {
            throw new IllegalArgumentException(
                    "Shopping location is required"
            );
        }

        double lat = req.getLat();
        double lon = req.getLon();

        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90 || lat > 90
                || lon < -180 || lon > 180) {
            throw new IllegalArgumentException(
                    "Invalid location coordinates"
            );
        }

        double radiusKm = req.getRadiusKm() != null
                ? req.getRadiusKm()
                : DEFAULT_RADIUS_KM;

        if (!Double.isFinite(radiusKm) || radiusKm <= 0) {
            throw new IllegalArgumentException(
                    "Radius must be greater than zero"
            );
        }

        BigDecimal costPerKm = req.getCostPerKm() != null
                ? req.getCostPerKm()
                : DEFAULT_COST_PER_KM;

        if (costPerKm.signum() < 0) {
            throw new IllegalArgumentException(
                    "Transport cost cannot be negative"
            );
        }

        if (maxPriceAgeDays <= 0) {
            throw new IllegalStateException(
                    "Price freshness period must be positive"
            );
        }

        List<ShoppingListItem> allItems =
                items.findByListIdOrderByIdAsc(listId).stream().filter(item -> !item.isPurchased()).toList();

        List<ShoppingListItem> pricedItems = allItems.stream()
                .filter(item -> item.getProductId() != null)
                .toList();

        int skipped = allItems.size() - pricedItems.size();

        if (pricedItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "List has no resolvable items to optimize"
            );
        }

        Map<Long, StoreCandidate> storesById =
                new LinkedHashMap<>();

        Map<Long, ItemCandidate> itemsById =
                new LinkedHashMap<>();

        String sql = """
            WITH user_point AS (
                SELECT ST_SetSRID(
                    ST_MakePoint(:lon, :lat), 4326
                )::geography AS g
            ),
            nearby_stores AS (
                SELECT
                    s.id,
                    s.name,
                    r.name AS retailer_name,
                    ST_Distance(
                        s.location::geography, up.g
                    ) / 1000.0 AS distance_km
                FROM stores s
                CROSS JOIN user_point up
                JOIN retailers r ON r.id = s.retailer_id
                WHERE s.active
                  AND r.active
                  AND s.store_code IS DISTINCT FROM 'ONLINE'
                  AND ST_DWithin(
                      s.location::geography,
                      up.g,
                      :radiusMeters
                  )
            )
            SELECT
                ns.id AS store_id,
                ns.name AS store_name,
                ns.retailer_name,
                ns.distance_km,
                li.id AS item_id,
                li.product_id,
                li.quantity,
                p.canonical_name AS product_name,
                offer.product_id AS offer_product_id,
                offer.product_name AS offer_product_name,
                offer.total
            FROM nearby_stores ns
            CROSS JOIN shopping_list_items li
            LEFT JOIN LATERAL shopping_list_item_offer(li, ns.id, :maxAgeDays) offer ON TRUE
            JOIN products p ON p.id = li.product_id
            WHERE li.list_id = :listId AND NOT li.purchased
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
                        .addValue("maxAgeDays", maxPriceAgeDays);

        jdbc.query(sql, params, rs -> {
            while (rs.next()) {
                long storeId = rs.getLong("store_id");
                long itemId = rs.getLong("item_id");

                StoreCandidate store =
                        storesById.computeIfAbsent(
                                storeId,
                                id -> {
                                    StoreCandidate s =
                                            new StoreCandidate();
                                    s.id = id;
                                    try {
                                        s.name = rs.getString(
                                                "store_name");
                                        s.retailerName = rs.getString(
                                                "retailer_name");
                                        s.distanceKm = rs.getDouble(
                                                "distance_km");
                                    } catch (SQLException ex) {
                                        throw new IllegalStateException(
                                                "Unable to read store", ex);
                                    }
                                    s.offersByItem = new HashMap<>();
                                    return s;
                                });

                ItemCandidate item =
                        itemsById.computeIfAbsent(
                                itemId,
                                id -> {
                                    ItemCandidate i =
                                            new ItemCandidate();
                                    i.itemId = id;
                                    try {
                                        i.productId = rs.getLong(
                                                "product_id");
                                        i.productName = rs.getString(
                                                "product_name");
                                        i.quantity = rs.getBigDecimal(
                                                "quantity");
                                        if (i.quantity == null) {
                                            i.quantity = BigDecimal.ONE;
                                        }
                                    } catch (SQLException ex) {
                                        throw new IllegalStateException(
                                                "Unable to read item", ex);
                                    }
                                    return i;
                                });

                if (item.quantity.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "Invalid quantity for item " + item.itemId);
                }

                BigDecimal total = rs.getBigDecimal("total");
                if (total != null) {
                    store.offersByItem.put(itemId, new ItemOffer(rs.getLong("offer_product_id"),
                            rs.getString("offer_product_name"), total));
                }
            }
            return null;
        });

        List<StoreCandidate> candidates =
                storesById.values().stream()
                        .filter(store ->
                                !store.offersByItem.isEmpty())
                        .sorted(Comparator.comparingLong(
                                store -> store.id))
                        .toList();

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "No fresh, in-stock prices found within "
                            + radiusKm + " km"
            );
        }

        Comparator<OptimizationPlan> rank =
                Comparator.comparingInt(
                                OptimizationPlan::getMissingItems)
                        .thenComparing(
                                OptimizationPlan::getGrandTotal)
                        .thenComparingInt(
                                plan -> plan.getStores().size());

        Map<Integer, OptimizationPlan> best =
                new HashMap<>();

        evaluateSubsets(
                candidates, 0, new ArrayList<>(),
                itemsById.values(), costPerKm,
                best, rank
        );

        OptimizationPlan recommendation =
                best.values().stream()
                        .min(rank)
                        .orElseThrow();

        int minimumMissing =
                recommendation.getMissingItems();

        best.values().removeIf(plan ->
                plan.getMissingItems() != minimumMissing);

        OptimizationPlan best1 = best.get(1);
        OptimizationPlan best2 = best.get(2);
        OptimizationPlan best3 = best.get(3);

        BigDecimal savings = BigDecimal.ZERO;
        BigDecimal savingsPct = BigDecimal.ZERO;

        if (best1 != null
                && best1.getMissingItems() == minimumMissing
                && best1.getGrandTotal().signum() > 0) {

            savings = best1.getGrandTotal()
                    .subtract(recommendation.getGrandTotal())
                    .max(BigDecimal.ZERO);

            savingsPct = savings
                    .multiply(new BigDecimal("100"))
                    .divide(
                            best1.getGrandTotal(),
                            1,
                            RoundingMode.HALF_UP);
        }

        return new OptimizeResponse(
                recommendation, best1, best2, best3,
                savings, savingsPct,
                costPerKm.doubleValue(), radiusKm,
                pricedItems.size(), skipped
        );
    }

    private void evaluateSubsets(
            List<StoreCandidate> candidates,
            int start,
            List<StoreCandidate> subset,
            Collection<ItemCandidate> allItems,
            BigDecimal costPerKm,
            Map<Integer, OptimizationPlan> best,
            Comparator<OptimizationPlan> rank
    ) {
        for (int i = start; i < candidates.size(); i++) {
            subset.add(candidates.get(i));

            OptimizationPlan plan =
                    buildPlan(subset, allItems, costPerKm);

            int storeCount = plan.getStores().size();

            if (storeCount > 0) {
                best.merge(
                        storeCount,
                        plan,
                        (previous, current) ->
                                rank.compare(previous, current) <= 0
                                        ? previous : current
                );
            }

            if (subset.size() < MAX_SPLIT_STORES) {
                evaluateSubsets(
                        candidates, i + 1, subset,
                        allItems, costPerKm, best, rank
                );
            }

            subset.remove(subset.size() - 1);
        }
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal minimumPositive(
            BigDecimal first,
            BigDecimal second
    ) {
        if (!isPositive(first)) {
            return isPositive(second) ? second : null;
        }
        if (!isPositive(second)) {
            return first;
        }
        return first.min(second);
    }

    static BigDecimal lineCost(
            PriceData price,
            BigDecimal quantity
    ) {
        if (price == null || quantity == null
                || quantity.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Invalid price or quantity"
            );
        }

        BigDecimal unitPrice = minimumPositive(
                price.shelfPrice(),
                price.regularPromo()
        );

        if (unitPrice == null) {
            throw new IllegalArgumentException(
                    "No valid unit price"
            );
        }

        return za.co.shoppingdiary.price.OfferPricing.total(
                unitPrice, quantity, price.multiBuyPromo(), price.minQuantity());
    }

    private OptimizationPlan buildPlan(
            List<StoreCandidate> subset,
            Collection<ItemCandidate> allItems,
            BigDecimal costPerKm
    ) {
        Map<Long, Long> itemToStore = new HashMap<>();
        Map<Long, ItemOffer> chosenOffers = new HashMap<>();

        BigDecimal productCost = BigDecimal.ZERO;
        int missing = 0;

        for (ItemCandidate item : allItems) {
            Long bestStore = null;
            ItemOffer best = null;
            for (StoreCandidate store : subset) {
                ItemOffer offer = store.offersByItem.get(item.itemId);
                if (offer != null && (best == null || offer.total().compareTo(best.total()) < 0)) {
                    best = offer;
                    bestStore = store.id;
                }
            }

            if (bestStore == null) {
                missing++;
                continue;
            }

            itemToStore.put(item.itemId, bestStore);
            chosenOffers.put(item.itemId, best);
            productCost = productCost.add(best.total());
        }

        Map<Long, BigDecimal> subtotals = new HashMap<>();

        for (ItemCandidate item : allItems) {
            Long storeId = itemToStore.get(item.itemId);
            if (storeId != null) {
                subtotals.merge(
                        storeId,
                        chosenOffers.get(item.itemId).total(),
                        BigDecimal::add);
            }
        }

        List<PlanStore> planStores = new ArrayList<>();
        BigDecimal transportTotal = BigDecimal.ZERO;

        for (StoreCandidate store : subset) {
            BigDecimal subtotal = subtotals.get(store.id);
            if (subtotal == null) {
                continue;
            }

            BigDecimal transport =
                    BigDecimal.valueOf(store.distanceKm)
                            .multiply(new BigDecimal("2"))
                            .multiply(costPerKm)
                            .setScale(2, RoundingMode.HALF_UP);

            transportTotal = transportTotal.add(transport);

            planStores.add(new PlanStore(
                    store.id,
                    store.name,
                    store.retailerName,
                    Math.round(store.distanceKm * 100.0) / 100.0,
                    transport,
                    subtotal
            ));
        }

        planStores.sort(Comparator.comparing(
                PlanStore::getDistanceKm));

        Map<Long, String> storeNames = new HashMap<>();

        planStores.forEach(store ->
                storeNames.put(
                        store.getStoreId(),
                        store.getStoreName()));

        List<PlanItem> planItems = new ArrayList<>();

        for (ItemCandidate item : allItems) {
            Long storeId = itemToStore.get(item.itemId);
            if (storeId == null) {
                continue;
            }

            ItemOffer chosen = chosenOffers.get(item.itemId);
            BigDecimal total = chosen.total();

            BigDecimal unitPrice = total.divide(
                    item.quantity, 2, RoundingMode.HALF_UP);

            var planItem = new PlanItem(
                    chosen.productId(),
                    chosen.name(),
                    item.quantity,
                    storeId,
                    storeNames.get(storeId),
                    unitPrice,
                    total
            );
            if (chosen.productId() != item.productId) planItem.setSubstitutedFor(item.productName);
            planItems.add(planItem);
        }

        BigDecimal grandTotal =
                productCost.add(transportTotal);

        return new OptimizationPlan(
                planStores,
                planItems,
                productCost,
                transportTotal,
                grandTotal,
                missing
        );
    }

    record PriceData(
            BigDecimal shelfPrice,
            BigDecimal regularPromo,
            BigDecimal multiBuyPromo,
            Integer minQuantity
    ) {}

    private record ItemOffer(long productId, String name, BigDecimal total) {}

    static class StoreCandidate {
        Long id;
        String name;
        String retailerName;
        double distanceKm;
        Map<Long, ItemOffer> offersByItem;
    }

    static class ItemCandidate {
        Long itemId;
        Long productId;
        String productName;
        BigDecimal quantity;
    }
}
