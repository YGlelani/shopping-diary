package za.co.shoppingdiary.promotion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import za.co.shoppingdiary.ingestion.api.service.ReefApiService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpecialsImportService {
    private final ReefApiService reef;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ReentrantLock importLock = new ReentrantLock();

    @Value("${shopping-diary.promotions.reef-enabled:false}")
    private boolean enabled;
    @Value("${reefapi.pnp.store-code:}")
    private String scheduledStore;

    public record Run(long id,String provider,String retailerSlug,String storeCode,String status,String summary,
                      String errorMessage,OffsetDateTime startedAt,OffsetDateTime finishedAt) {}

    @Scheduled(cron="${shopping-diary.promotions.reef-cron:0 25 5 * * *}",zone="Africa/Johannesburg")
    public void scheduled() {
        if (!enabled || scheduledStore==null || scheduledStore.isBlank()) return;
        try {
            collect("picknpay",scheduledStore);
        } catch (RuntimeException failure) {
            log.warn("Scheduled specials import failed. See the promotion import history.");
        }
    }

    public Map<String,Object> collect(String retailer,String branch) {
        if (!importLock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT,"An import is already running");
        try {
            long id = jdbc.sql("""
                INSERT INTO promotion_import_runs(provider,retailer_slug,store_code,status)
                VALUES ('REEFAPI',:retailer,:store,'RUNNING') RETURNING id
                """).param("retailer",bounded(retailer)).param("store",bounded(branch)).query(Long.class).single();
            try {
                var result = reef.syncPromotions(retailer,branch);
                jdbc.sql("""
                    UPDATE promotion_import_runs SET status='SUCCEEDED',summary=CAST(:summary AS jsonb),finished_at=now() WHERE id=:id
                    """).param("summary",json.writeValueAsString(result)).param("id",id).update();
                return result;
            } catch (RuntimeException error) {
                String message = error instanceof ResponseStatusException status ? status.getReason() : "Promotion import failed";
                jdbc.sql("""
                    UPDATE promotion_import_runs SET status='FAILED',error_message=:message,finished_at=now() WHERE id=:id
                    """).param("message",message).param("id",id).update();
                throw error;
            }
        } finally {
            importLock.unlock();
        }
    }

    public List<Run> runs() {
        return jdbc.sql("""
            SELECT id,provider,retailer_slug,store_code,status,summary::text,error_message,started_at,finished_at
            FROM promotion_import_runs ORDER BY id DESC LIMIT 25
            """).query(Run.class).list();
    }

    private static String bounded(String value) {
        return value==null ? null : value.substring(0,Math.min(80,value.length()));
    }
}
