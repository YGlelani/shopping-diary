
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

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShoppingListService {

    private final ShoppingListRepository lists;
    private final ShoppingListItemRepository items;
    private final ProductRepository products;
    private final NamedParameterJdbcTemplate jdbc;

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxPriceAgeDays;

    @Transactional
    public ListDetailResponse create(
            Long userId,
            CreateListRequest req
    ) {
        if (req == null || req.getName() == null
                || req.getName().isBlank()) {
            throw new IllegalArgumentException(
                    "Shopping list name is required"
            );
        }

        ShoppingList list = new ShoppingList();
        list.setUserId(userId);
        list.setName(req.getName().trim());
        list.setBudgetAmount(req.getBudgetAmount());

        lists.save(list);

        return toDetail(list);
    }

    @Transactional(readOnly = true)
    public List<ListDetailResponse> mine(Long userId) {
        return lists
                .findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toDetail)
                .toList();
    }

    @Transactional(readOnly = true)
    public ListDetailResponse detail(
            Long userId,
            Long listId
    ) {
        return toDetail(ownedList(userId, listId));
    }

    @Transactional
    public ListDetailResponse.Item addItem(
            Long userId,
            Long listId,
            AddItemRequest req
    ) {
        ownedList(userId, listId);

        if (req == null || req.getProductId() == null) {
            throw new IllegalArgumentException(
                    "Product is required"
            );
        }

        Product product = products
                .findById(req.getProductId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown product"
                        )
                );

        ShoppingListItem item = new ShoppingListItem();
        item.setListId(listId);
        item.setProductId(product.getId());

        if (req.getQuantity() != null) {
            if (req.getQuantity().signum() <= 0) {
                throw new IllegalArgumentException(
                        "Quantity must be positive"
                );
            }
            item.setQuantity(req.getQuantity());
        }

        item.setUnit(req.getUnit());
        items.save(item);

        return toItem(item, product);
    }

    @Transactional
    public void incrementItem(
            Long userId,
            Long listId,
            Long itemId
    ) {
        ownedList(userId, listId);

        int updated = jdbc.update(
                """
                UPDATE shopping_list_items
                SET quantity = quantity + 1
                WHERE list_id = :listId
                  AND id = :itemId
                """,
                new MapSqlParameterSource()
                        .addValue("listId", listId)
                        .addValue("itemId", itemId)
        );

        if (updated == 0) {
            throw new IllegalArgumentException(
                    "Item not found in this list"
            );
        }
    }

    @Transactional
    public void removeItem(
            Long userId,
            Long listId,
            Long itemId
    ) {
        ownedList(userId, listId);

        ShoppingListItem item = items
                .findByIdAndListId(itemId, listId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Item not found in this list"
                        )
                );

        items.delete(item);
    }

    @Transactional
    public void delete(
            Long userId,
            Long listId
    ) {
        ShoppingList list = ownedList(userId, listId);

        items.deleteAll(
                items.findByListIdOrderByIdAsc(listId)
        );

        lists.delete(list);
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
                COUNT(ep.effective_price) AS covered_items,
                COALESCE(
                    SUM(line.total),
                    0
                ) AS subtotal
            FROM nearby_stores ns
            CROSS JOIN shopping_list_items li
            LEFT JOIN LATERAL current_branch_price(li.product_id, ns.id, :maxAgeDays) ep ON TRUE
            LEFT JOIN LATERAL (
                SELECT round(least(li.quantity * ep.effective_price,
                    min(p.promo_price * trunc(li.quantity / p.min_quantity)
                        + ep.effective_price * mod(li.quantity, p.min_quantity))), 2) AS total
                FROM current_branch_promotions p
                WHERE p.product_id = li.product_id AND p.store_id = ns.id AND p.promo_type = 'MULTIBUY'
            ) line ON ep.effective_price IS NOT NULL
            WHERE li.list_id = :listId
            GROUP BY
                ns.id,
                ns.name,
                ns.retailer_name,
                ns.distance_m
            HAVING COUNT(ep.effective_price) > 0
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

    private ShoppingList ownedList(
            Long userId,
            Long listId
    ) {
        return lists
                .findByIdAndUserId(listId, userId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "List not found"
                        )
                );
    }

    private ListDetailResponse toDetail(
            ShoppingList list
    ) {
        ListDetailResponse response =
                new ListDetailResponse();

        response.setId(list.getId());
        response.setName(list.getName());
        response.setBudgetAmount(
                list.getBudgetAmount()
        );
        response.setStatus(list.getStatus());
        response.setCreatedAt(list.getCreatedAt());

        response.setItems(
                items.findByListIdOrderByIdAsc(list.getId())
                        .stream()
                        .map(item ->
                                toItem(
                                        item,
                                        item.getProductId() == null
                                                ? null
                                                : products.findById(
                                                        item.getProductId()
                                                ).orElse(null)
                                )
                        )
                        .toList()
        );

        return response;
    }

    private ListDetailResponse.Item toItem(
            ShoppingListItem item,
            Product product
    ) {
        ListDetailResponse.Item response =
                new ListDetailResponse.Item();

        response.setId(item.getId());
        response.setProductId(
                item.getProductId()
        );

        if (product != null) {
            response.setProductName(
                    product.getCanonicalName()
            );
            response.setBrand(product.getBrand());
            response.setSizeText(
                    product.getSizeText()
            );
        } else {
            response.setProductName(
                    item.getFreeText()
            );
        }

        response.setQuantity(item.getQuantity());
        response.setUnit(item.getUnit());

        return response;
    }
}
