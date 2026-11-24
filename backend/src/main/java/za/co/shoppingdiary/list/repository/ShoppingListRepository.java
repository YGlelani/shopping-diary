package za.co.shoppingdiary.list.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import za.co.shoppingdiary.list.entity.ShoppingList;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface ShoppingListRepository extends JpaRepository<ShoppingList, Long> {
    List<ShoppingList> findByUserIdOrderByCreatedAtDesc(Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ShoppingList l where l.id = :id and l.userId = :userId")
    Optional<ShoppingList> lockOwned(Long id, Long userId);

    Optional<ShoppingList> findByUserIdAndCreationRequestId(Long userId, java.util.UUID requestId);

    Optional<ShoppingList> findByIdAndUserId(Long id, Long userId);
}