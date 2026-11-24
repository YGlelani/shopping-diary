package za.co.shoppingdiary.list.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import za.co.shoppingdiary.list.entity.ShoppingListItem;

import java.util.List;
import java.util.Optional;

public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, Long> {
    List<ShoppingListItem> findByListIdOrderByIdAsc(Long listId);
    List<ShoppingListItem> findByListIdInOrderByIdAsc(List<Long> listIds);
    long countByListId(Long listId);
    Optional<ShoppingListItem> findByIdAndListId(Long id, Long listId);
}