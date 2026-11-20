package za.co.shoppingdiary.alert.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.shoppingdiary.alert.dto.CreateAlertRequest;
import za.co.shoppingdiary.alert.entity.PriceAlert;
import za.co.shoppingdiary.alert.repository.PriceAlertRepository;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAlertService {

    private final PriceAlertRepository alerts;
    private final JdbcTemplate jdbc;

    @Transactional
    public void createOrUpdateAlert(Long userId, CreateAlertRequest req) {
        PriceAlert alert = alerts.findByUserIdAndProductId(userId, req.getProductId())
                .orElseGet(() -> {
                    PriceAlert newAlert = new PriceAlert();
                    newAlert.setUserId(userId);
                    newAlert.setProductId(req.getProductId());
                    return newAlert;
                });

        alert.setTargetPrice(req.getTargetPrice());
        alert.setActive(true);
        alert.setLastNotifiedAt(null);
        alerts.save(alert);
    }

    public List<PriceAlert> getUserAlerts(Long userId) {
        return alerts.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void deleteAlert(Long userId, Long alertId) {
        alerts.deleteByIdAndUserId(alertId, userId);
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Africa/Johannesburg")
    public void checkAlerts() {
        int triggered = jdbc.update("""
            WITH latest_prices AS (
                SELECT DISTINCT ON (po.product_id, po.store_id)
                       po.product_id, LEAST(po.promo_price, po.shelf_price) AS price
                FROM price_observations po
                JOIN stores s ON s.id = po.store_id AND s.active
                WHERE po.observed_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'
                  AND EXISTS (
                      SELECT 1 FROM price_alerts a WHERE a.active AND a.product_id = po.product_id
                  )
                ORDER BY po.product_id, po.store_id, po.observed_at DESC, po.id DESC
            ), cheapest AS (
                SELECT product_id, MIN(price) AS price FROM latest_prices GROUP BY product_id
            )
            UPDATE price_alerts a
            SET active = false, last_notified_at = CURRENT_TIMESTAMP
            FROM cheapest c
            WHERE a.active AND a.product_id = c.product_id AND c.price <= a.target_price
            """);
        log.info("Triggered {} in-app price alerts", triggered);
    }
}
