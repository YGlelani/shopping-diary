
package za.co.shoppingdiary.alert.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${shopping-diary.prices.max-age-days:7}")
    private int maxPriceAgeDays;

    @Transactional
    public void createOrUpdateAlert(
            Long userId,
            CreateAlertRequest request
    ) {
        if (userId == null || request == null
                || request.getProductId() == null
                || request.getProductId() <= 0
                || request.getTargetPrice() == null
                || request.getTargetPrice()
                        .compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "A valid product and positive target price are required"
            );
        }

        Boolean productExists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM products WHERE id = ?)",
                Boolean.class,
                request.getProductId()
        );

        if (!Boolean.TRUE.equals(productExists)) {
            throw new IllegalArgumentException(
                    "Product does not exist"
            );
        }

        PriceAlert alert = alerts
                .findByUserIdAndProductId(
                        userId,
                        request.getProductId()
                )
                .orElseGet(() -> {
                    PriceAlert created = new PriceAlert();
                    created.setUserId(userId);
                    created.setProductId(
                            request.getProductId()
                    );
                    return created;
                });

        alert.setTargetPrice(request.getTargetPrice());
        alert.setActive(true);
        alert.setLastNotifiedAt(null);

        alerts.save(alert);
    }

    @Transactional(readOnly = true)
    public List<PriceAlert> getUserAlerts(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "User is required"
            );
        }

        return alerts.findByUserIdOrderByCreatedAtDesc(
                userId
        );
    }

    @Transactional
    public void deleteAlert(Long userId, Long alertId) {
        if (userId == null || alertId == null) {
            throw new IllegalArgumentException(
                    "User and alert ID are required"
            );
        }

        alerts.deleteByIdAndUserId(alertId, userId);
    }

    @Scheduled(
            cron = "0 0 8 * * *",
            zone = "Africa/Johannesburg"
    )
    @Transactional
    public void checkAlerts() {

        if (maxPriceAgeDays <= 0) {
            throw new IllegalStateException(
                    "Invalid price freshness configuration"
            );
        }

        String sql = """
            WITH latest_observations AS (
                SELECT DISTINCT ON (
                    po.product_id,
                    po.store_id
                )
                    po.product_id,
                    po.store_id,
                    po.shelf_price,
                    po.promo_price,
                    po.observed_at,
                    po.stock_status,
                    po.verification_status
                FROM price_observations po
                JOIN stores s
                    ON s.id = po.store_id
                   AND s.active = TRUE
                JOIN retailers r
                    ON r.id = s.retailer_id
                   AND r.active = TRUE
                WHERE EXISTS (
                    SELECT 1
                    FROM price_alerts a
                    WHERE a.product_id = po.product_id
                      AND a.active = TRUE
                )
                ORDER BY
                    po.product_id,
                    po.store_id,
                    po.observed_at DESC,
                    po.id DESC
            ),
            eligible AS (
                SELECT
                    product_id,
                    CASE
                        WHEN promo_price > 0
                             AND shelf_price > 0
                            THEN LEAST(
                                promo_price,
                                shelf_price
                            )
                        WHEN promo_price > 0
                            THEN promo_price
                        WHEN shelf_price > 0
                            THEN shelf_price
                        ELSE NULL
                    END AS effective_price
                FROM latest_observations
                WHERE observed_at >= (
                    CURRENT_TIMESTAMP
                    - (? * INTERVAL '1 day')
                )
                  AND observed_at <= CURRENT_TIMESTAMP
                  AND stock_status = 'IN_STOCK'
                  AND verification_status IN (
                      'VERIFIED',
                      'UNVERIFIED'
                  )
            ),
            cheapest AS (
                SELECT
                    product_id,
                    MIN(effective_price) AS price
                FROM eligible
                WHERE effective_price IS NOT NULL
                GROUP BY product_id
            )
            UPDATE price_alerts a
            SET last_notified_at = CURRENT_TIMESTAMP
            FROM cheapest c
            WHERE a.product_id = c.product_id
              AND a.active = TRUE
              AND c.price <= a.target_price
              AND (
                  a.last_notified_at IS NULL
                  OR a.last_notified_at <
                     CURRENT_TIMESTAMP - INTERVAL '24 hours'
              )
            """;

        int matched = jdbc.update(sql, maxPriceAgeDays);

        log.info(
                "{} price alerts matched eligible offers",
                matched
        );
    }
}
