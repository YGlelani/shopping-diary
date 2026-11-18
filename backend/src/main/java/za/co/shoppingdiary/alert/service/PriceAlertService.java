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

import java.math.BigDecimal;
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
        alerts.save(alert);
    }

    public List<PriceAlert> getUserAlerts(Long userId) {
        return alerts.findByUserIdAndActiveTrue(userId);
    }

    @Scheduled(cron = "0 0 8 * * *") // Runs daily at 8 AM
    public void checkAlerts() {
        log.info("Starting daily price alert check...");
        List<PriceAlert> activeAlerts = alerts.findAll().stream().filter(PriceAlert::isActive).toList();

        for (PriceAlert alert : activeAlerts) {
            BigDecimal cheapestPrice = jdbc.queryForObject("""
                SELECT MIN(COALESCE(promo_price, shelf_price))
                FROM price_observations
                WHERE product_id = ?
                """, BigDecimal.class, alert.getProductId());

            if (cheapestPrice != null && cheapestPrice.compareTo(alert.getTargetPrice()) <= 0) {
                log.info("🚨 ALERT TRIGGERED for User {}: Product {} is now R{} (Target was R{})",
                        alert.getUserId(), alert.getProductId(), cheapestPrice, alert.getTargetPrice());
                
                alert.setActive(false);
                alerts.save(alert);
            }
        }
    }
}