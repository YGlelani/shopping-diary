package za.co.shoppingdiary.ingestion.automation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.time.*;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class GroceryAutomation {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final BulkGroceryFiles files;
    private final ScopedPriceIntake intake;
    private final List<PriceDataSource> sources;
    private final LoyaltyHubQuota quota;

    @Value("${GROCERY_AUTOMATION_SCHEDULE_ENABLED:false}") private boolean schedule;
    @Value("${GROCERY_OFF_EXPORT_FILE:}") private String catalogueFile;
    @Value("${GROCERY_OSM_EXTRACT_FILE:}") private String storesFile;
    @Value("${GROCERY_LOYALTY_QUERY:}") private String loyaltyQuery;

    public record Run(long id,String source,boolean dryRun,String status,int examined,int imported,int skipped,
                      int review,String checkpoint,String detail,OffsetDateTime startedAt,OffsetDateTime finishedAt) {}
    public record Review(String source,String sourceKey,String reason,String label,OffsetDateTime createdAt) {}
    public record SourceStatus(String source,boolean enabled,OffsetDateTime lastSuccess,Run lastRun) {}
    private record Cursor(int nextOffset,OffsetDateTime watermark,OffsetDateTime scanWatermark) {}

    public List<SourceStatus> sources() {
        var result=new java.util.ArrayList<SourceStatus>();
        result.add(status(BulkGroceryFiles.CATALOGUE,!catalogueFile.isBlank()));
        result.add(status(BulkGroceryFiles.BRANCHES,!storesFile.isBlank()));
        sources.forEach(source -> result.add(status(source.name(),source.enabled())));
        return result;
    }

    private SourceStatus status(String source,boolean enabled) {
        var last=jdbc.sql("SELECT * FROM grocery_import_runs WHERE source=:source ORDER BY id DESC LIMIT 1")
                .param("source",source).query(Run.class).optional().orElse(null);
        var success=jdbc.sql("""
            SELECT finished_at FROM grocery_import_runs WHERE source=:source AND NOT dry_run
              AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1
            """).param("source",source).query(OffsetDateTime.class).optional().orElse(null);
        return new SourceStatus(source,enabled,success,last);
    }

    public List<Run> history() {
        return jdbc.sql("SELECT * FROM grocery_import_runs ORDER BY id DESC LIMIT 30").query(Run.class).list();
    }

    public List<Review> reviews() {
        return jdbc.sql("SELECT * FROM grocery_import_review ORDER BY created_at DESC LIMIT 50").query(Review.class).list();
    }

    public LoyaltyHubQuota.Usage quota() { return quota.usage(); }

    public Run file(String source,InputStream input,String filename,boolean dry) {
        return run(source,dry,() -> read(source,input,filename,dry));
    }

    public Run price(String source,String query,boolean dry) {
        var provider=sources.stream().filter(item -> item.name().equals(source)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown price source"));
        if ("LOYALTYHUB".equals(source) && (query==null || query.isBlank() || query.length()>120))
            throw new IllegalArgumentException("Enter a search of 1 to 120 characters");
        var search=query==null ? "" : query.strip();
        return run(source,dry,() -> {
            var key=search.toLowerCase(Locale.ROOT);
            var cursor=jdbc.sql("""
                SELECT next_offset,watermark,scan_watermark FROM grocery_price_checkpoints
                WHERE source=:source AND search_key=:key
                """).param("source",source).param("key",key).query(Cursor.class).optional().orElse(new Cursor(0,null,null));
            var since=cursor.watermark()==null ? null : cursor.watermark().toInstant().minus(Duration.ofDays(1));
            var page=provider.collect(search,dry ? 0 : cursor.nextOffset(),dry ? null : since);
            var report=intake.accept(source,page.rows(),dry);
            if (!dry) {
                var window=cursor.scanWatermark()==null ? ScopedPriceIntake.date(page.updatedAt()) : cursor.scanWatermark();
                var watermark=page.nextOffset()==null && window!=null ? window : cursor.watermark();
                int next=page.nextOffset()==null ? 0 : page.nextOffset();
                jdbc.sql("""
                    INSERT INTO grocery_price_checkpoints(source,search_key,next_offset,watermark,scan_watermark)
                    VALUES (:source,:key,:next,:watermark,:window)
                    ON CONFLICT(source,search_key) DO UPDATE SET next_offset=excluded.next_offset,
                        watermark=excluded.watermark,scan_watermark=excluded.scan_watermark
                    """).param("source",source).param("key",key).param("next",next)
                        .param("watermark",watermark,Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("window",next==0 ? null : window,Types.TIMESTAMP_WITH_TIMEZONE).update();
                report.checkpoint="offset "+next+(watermark==null ? "" : " since "+watermark);
            }
            return report;
        });
    }

    @Scheduled(cron="${GROCERY_AUTOMATION_CRON:0 15 4 * * *}",zone="Africa/Johannesburg")
    public void scheduled() {
        if (!schedule) return;
        scheduledFile(BulkGroceryFiles.CATALOGUE,catalogueFile);
        scheduledFile(BulkGroceryFiles.BRANCHES,storesFile);
        if (!loyaltyQuery.isBlank() && sources.stream().anyMatch(s -> "LOYALTYHUB".equals(s.name()) && s.enabled())) {
            try { price("LOYALTYHUB",loyaltyQuery,false); }
            catch (RuntimeException ex) { log.warn("Loyalty Hub import did not complete. See the admin import history."); }
        }
        if (sources.stream().anyMatch(item -> "OPEN_PRICES".equals(item.name()) && item.enabled())) {
            try { price("OPEN_PRICES","",false); }
            catch (RuntimeException ex) { log.warn("Open Prices import did not complete. See the admin import history."); }
        }
        jdbc.sql("DELETE FROM grocery_scoped_prices WHERE source='LOYALTYHUB' AND collected_at<now()-interval '30 days'").update();
        jdbc.sql("DELETE FROM grocery_import_review WHERE source='LOYALTYHUB' AND created_at<now()-interval '30 days'").update();
    }

    public Run configuredFile(String source,boolean dry) {
        String configured=switch(source) {
            case BulkGroceryFiles.CATALOGUE -> catalogueFile;
            case BulkGroceryFiles.BRANCHES -> storesFile;
            default -> throw new IllegalArgumentException("Unknown bulk source");
        };
        if (configured.isBlank()) throw new IllegalArgumentException("No server-side export file configured");
        return run(source,dry,() -> {
            try {
                var file=Path.of(configured);
                var marker=file.getFileName()+"|"+Files.size(file)+"|"+Files.getLastModifiedTime(file).toMillis();
                boolean done=!dry && jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM grocery_import_runs WHERE source=:source AND checkpoint=:marker
                       AND status='SUCCEEDED' AND NOT dry_run)
                    """).param("source",source).param("marker",marker).query(Boolean.class).single();
                if (done) { var empty=new ImportCounts(); empty.skip("Export already processed"); return empty; }
                try (var input=Files.newInputStream(file)) {
                    var result=read(source,input,file.getFileName().toString(),dry);
                    if (!marker.equals(file.getFileName()+"|"+Files.size(file)+"|"+Files.getLastModifiedTime(file).toMillis()))
                        throw new IOException("Export changed while it was being read");
                    result.checkpoint=marker;
                    return result;
                }
            } catch (IOException ex) { throw new IllegalArgumentException("Cannot read the configured export file"); }
        });
    }

    private void scheduledFile(String source,String configured) {
        if (configured.isBlank()) return;
        try { configuredFile(source,false); }
        catch (RuntimeException ex) { log.warn("{} import did not complete. See the admin import history.",source); }
    }

    private ImportCounts read(String source,InputStream input,String filename,boolean dry) {
        try {
            return switch(source) {
                case BulkGroceryFiles.CATALOGUE -> files.catalogue(input,filename,dry);
                case BulkGroceryFiles.BRANCHES -> files.branches(input,filename,dry);
                default -> throw new IllegalArgumentException("Unknown bulk source");
            };
        } catch (IOException | UncheckedIOException ex) {
            throw new IllegalArgumentException("The export is incomplete, invalid or in an unsupported format");
        }
    }

    private Run run(String source,boolean dry,Supplier<ImportCounts> work) {
        var start=jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        try {
            return transactions.execute(transaction -> {
                requireLock(source);
                var result=work.get();
                return done(source,dry,"SUCCEEDED",result,start,result.detail());
            });
        } catch (RuntimeException error) {
            String detail=error instanceof ResponseStatusException status ? status.getReason()
                    : error instanceof IllegalArgumentException ? error.getMessage() : "Import failed. No batch changes were committed.";
            done(source,dry,error instanceof ResponseStatusException status && status.getStatusCode().value()==409 ? "SKIPPED" : "FAILED",
                    new ImportCounts(),start,SourceFields.clean(detail,600));
            if (error instanceof ResponseStatusException || error instanceof IllegalArgumentException) throw error;
            log.warn("{} import rolled back",source,error);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Import failed and was rolled back");
        }
    }

    public void purgeLoyalty() {
        transactions.executeWithoutResult(status -> {
            requireLock("LOYALTYHUB");
            jdbc.sql("DELETE FROM grocery_scoped_prices WHERE source='LOYALTYHUB'").update();
            jdbc.sql("DELETE FROM grocery_import_review WHERE source='LOYALTYHUB'").update();
            jdbc.sql("DELETE FROM grocery_import_runs WHERE source='LOYALTYHUB'").update();
            jdbc.sql("DELETE FROM grocery_price_checkpoints WHERE source='LOYALTYHUB'").update();
        });
    }

    private void requireLock(String source) {
        boolean locked=jdbc.sql("SELECT pg_try_advisory_xact_lock(93476,hashtext(:source))")
                .param("source",source).query(Boolean.class).single();
        if (!locked) throw new ResponseStatusException(HttpStatus.CONFLICT,"An import for this source is already running");
    }

    private Run done(String source,boolean dry,String status,ImportCounts counts,OffsetDateTime start,String detail) {
        return jdbc.sql("""
            INSERT INTO grocery_import_runs(source,dry_run,status,examined,imported,skipped,review,checkpoint,detail,started_at,finished_at)
            VALUES (:source,:dry,:status,:examined,:imported,:skipped,:review,:checkpoint,:detail,:start,clock_timestamp())
            RETURNING *
            """).param("source",source).param("dry",dry).param("status",status)
                .param("examined",counts.examined).param("imported",counts.imported).param("skipped",counts.skipped)
                .param("review",counts.review).param("checkpoint",counts.checkpoint).param("detail",detail)
                .param("start",start).query(Run.class).single();
    }
}
