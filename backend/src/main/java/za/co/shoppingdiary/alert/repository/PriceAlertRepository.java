package za.co.shoppingdiary.alert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.shoppingdiary.alert.entity.PriceAlert;
import java.util.List;
import java.util.Optional;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {
    List<PriceAlert> findByUserIdAndActiveTrue(Long userId);
    Optional<PriceAlert> findByUserIdAndProductId(Long userId, Long productId);
    List<PriceAlert> findByProductIdAndActiveTrue(Long productId);
}