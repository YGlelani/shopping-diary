package za.co.shoppingdiary.list;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, Long> {
    List<ShoppingListItem> findByListIdOrderByIdAsc(Long listId);
    long countByListId(Long listId);
    Optional<ShoppingListItem> findByIdAndListId(Long id, Long listId);
}