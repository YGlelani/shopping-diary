package za.co.shoppingdiary.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class KeepAliveScheduler {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveScheduler.class);
    private final RestTemplate restTemplate = new RestTemplate();

    // Runs every 10 minutes (600,000 ms), starting 1 minute after boot
    @Scheduled(fixedRate = 600000, initialDelay = 60000)
    public void pingSelf() {
        try {
            // Pings its own health endpoint on localhost
            restTemplate.getForObject("http://localhost:8080/actuator/health", String.class);
            log.debug("Self-ping successful.");
        } catch (Exception e) {
            log.warn("Self-ping failed: {}", e.getMessage());
        }
    }
}
