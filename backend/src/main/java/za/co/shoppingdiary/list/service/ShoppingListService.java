package za.co.shoppingdiary.list.service;

import lombok.RequiredArgsConstructor;
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

    @Transactional
    public ListDetailResponse create(Long userId, CreateListRequest req) {
        ShoppingList list = new ShoppingList();
        list.setUserId(userId);
        list.setName(req.getName().trim());
        list.setBudgetAmount(req.getBudgetAmount());
        lists.save(list);
        return toDetail(list);
    }

    public List<ListDetailResponse> mine(Long userId) {
        return lists.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDetail)
                .toList();
    }

    public ListDetailResponse detail(Long userId, Long listId) {
        ShoppingList list = ownedList(userId, listId);
        return toDetail(list);
    }

    @Transactional
    public ListDetailResponse.Item addItem(Long userId, Long listId, AddItemRequest req) {
        ownedList(userId, listId);
        Product product = products.findById(req.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown product"));

        ShoppingListItem item = new ShoppingListItem();
        item.setListId(listId);
        item.setProductId(product.getId());
        if (req.getQuantity() != null) item.setQuantity(req.getQuantity());
        item.setUnit(req.getUnit());
        items.save(item);

        return toItem(item, product);
    }

    @Transactional
    public void removeItem(Long userId, Long listId, Long itemId) {
        ownedList(userId, listId);
        ShoppingListItem item = items.findByIdAndListId(itemId, listId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found in this list"));
        items.delete(item);
    }

    @Transactional
    public void delete(Long userId, Long listId) {
        ShoppingList list = ownedList(userId, listId);
        items.deleteAll(items.findByListIdOrderByIdAsc(listId));
        lists.delete(list);
    }

    public List<BasketRow> baskets(Long userId, Long listId, double lat, double lon, double radiusKm) {
        ownedList(userId, listId);

        int totalItems = (int) items.countByListId(listId);
        if (totalItems == 0) return List.of();

        String sql = """
            WITH user_point AS (
                SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS g
            ),
            nearby_stores AS (
                SELECT s.id, s.name, r.name AS retailer_name,
                       ST_Distance(s.location::geography, up.g) AS distance_m
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
            SELECT ns.id AS store_id,
                   ns.name AS store_name,
                   ns.retailer_name,
                   ns.distance_m,
                   COUNT(lp.effective_price) AS covered_items,
                   SUM(li.quantity * lp.effective_price) AS subtotal
            FROM nearby_stores ns
            CROSS JOIN shopping_list_items li
            LEFT JOIN latest_price lp
                   ON lp.product_id = li.product_id
                  AND lp.store_id = ns.id
            WHERE li.list_id = :listId
            GROUP BY ns.id, ns.name, ns.retailer_name, ns.distance_m
            HAVING COUNT(lp.effective_price) > 0
            ORDER BY subtotal ASC NULLS LAST, ns.distance_m ASC
            """;

        var params = new MapSqlParameterSource()
                .addValue("listId", listId)
                .addValue("lat", lat)
                .addValue("lon", lon)
                .addValue("radiusMeters", radiusKm * 1000);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            BasketRow row = new BasketRow();
            row.setStoreId(rs.getLong("store_id"));
            row.setStoreName(rs.getString("store_name"));
            row.setRetailerName(rs.getString("retailer_name"));
            row.setDistanceMeters(rs.getDouble("distance_m"));
            row.setSubtotal(rs.getBigDecimal("subtotal"));
            int covered = rs.getInt("covered_items");
            row.setCoveredItems(covered);
            row.setTotalItems(totalItems);
            row.setMissingItems(totalItems - covered);
            row.setCoveragePct(totalItems == 0 ? 0.0
                    : Math.round(1000.0 * covered / totalItems) / 10.0);
            return row;
        });
    }

    // ---- helpers ----

    private ShoppingList ownedList(Long userId, Long listId) {
        return lists.findByIdAndUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("List not found"));
    }

    private ListDetailResponse toDetail(ShoppingList list) {
        ListDetailResponse r = new ListDetailResponse();
        r.setId(list.getId());
        r.setName(list.getName());
        r.setBudgetAmount(list.getBudgetAmount());
        r.setStatus(list.getStatus());
        r.setCreatedAt(list.getCreatedAt());
        r.setItems(items.findByListIdOrderByIdAsc(list.getId()).stream()
                .map(i -> toItem(i, i.getProductId() == null
                        ? null
                        : products.findById(i.getProductId()).orElse(null)))
                .toList());
        return r;
    }

    private ListDetailResponse.Item toItem(ShoppingListItem item, Product product) {
        ListDetailResponse.Item r = new ListDetailResponse.Item();
        r.setId(item.getId());
        r.setProductId(item.getProductId());
        if (product != null) {
            r.setProductName(product.getCanonicalName());
            r.setBrand(product.getBrand());
            r.setSizeText(product.getSizeText());
        } else {
            r.setProductName(item.getFreeText());
        }
        r.setQuantity(item.getQuantity());
        r.setUnit(item.getUnit());
        return r;
    }
}