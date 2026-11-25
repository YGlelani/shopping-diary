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
    private final LoyaltyHubSource loyaltySource;
    private final LoyaltyCatalogue catalogue;

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

    public record Discovery(long id,String name,String category,String keywords,int priority,int target,
                            long variants,OffsetDateTime nextRun,OffsetDateTime lastRun,int failures,
                            String departmentFilter,String retailerFilter,String regionFilter) {}
    public record Coverage(long products,long pictured,long missingBarcodes,long latestPrices,long stalePrices) {}
    public record DiscoveryConfig(String name,String category,String keywords,int priority,int target,
                                  String departmentFilter,String retailerFilter,String regionFilter) {}

    private static final String DISCOVERY_SELECT = """
        SELECT t.*, (SELECT count(*) FROM loyalty_generic_variants v WHERE v.generic_id=t.id) AS variants
        FROM loyalty_generic_types t
        """;

    public List<Discovery> discovery() {
        return jdbc.sql(DISCOVERY_SELECT+" ORDER BY priority,name").query(Discovery.class).list();
    }

    public Coverage coverage() {
        return jdbc.sql("""
            SELECT (SELECT count(*) FROM loyalty_product_metadata) AS products,
                (SELECT count(*) FROM loyalty_product_metadata m JOIN products p ON p.id=m.product_id WHERE p.image_url IS NOT NULL) AS pictured,
                (SELECT count(*) FROM loyalty_product_metadata m JOIN products p ON p.id=m.product_id WHERE p.barcode IS NULL) AS missing_barcodes,
                (SELECT count(*) FROM loyalty_latest_prices) AS latest_prices,
                (SELECT count(*) FROM loyalty_latest_prices WHERE observed_at<now()-interval '24 hours') AS stale_prices
            """).query(Coverage.class).single();
    }

    public record CoverageGroup(String type,String label,long products) {}
    public record IncompleteProduct(long id,String name,boolean missingImage,boolean missingBarcode) {}

    public List<CoverageGroup> coverageGroups() {
        return jdbc.sql("""
            SELECT 'Retailer' AS type,r.name AS label,count(DISTINCT p.product_id) AS products
            FROM loyalty_latest_prices p JOIN retailers r ON r.id=p.retailer_id
            GROUP BY r.id,r.name
            UNION ALL
            SELECT 'Category',t.category,count(DISTINCT v.product_id)
            FROM loyalty_generic_types t LEFT JOIN loyalty_generic_variants v ON v.generic_id=t.id GROUP BY t.category
            ORDER BY type,label
            """).query(CoverageGroup.class).list();
    }

    public List<IncompleteProduct> incompleteProducts() {
        return jdbc.sql("""
            SELECT p.id,p.canonical_name AS name,p.image_url IS NULL AS missing_image,p.barcode IS NULL AS missing_barcode
            FROM loyalty_product_metadata m JOIN products p ON p.id=m.product_id
            WHERE p.image_url IS NULL OR p.barcode IS NULL ORDER BY p.canonical_name,p.id LIMIT 50
            """).query(IncompleteProduct.class).list();
    }

    public boolean paused() {
        return jdbc.sql("SELECT paused FROM loyalty_catalogue_state WHERE singleton").query(Boolean.class).single();
    }

    public void pause(boolean value) {
        transactions.executeWithoutResult(tx -> {
            requireLock("LOYALTYHUB");
            jdbc.sql("UPDATE loyalty_catalogue_state SET paused=:paused WHERE singleton").param("paused",value).update();
        });
    }

    public void configure(List<DiscoveryConfig> entries) {
        if (entries.isEmpty() || entries.size()>500) throw new IllegalArgumentException("Provide 1 to 500 product types");
        transactions.executeWithoutResult(tx -> {
            requireLock("LOYALTYHUB");
            for (var entry : entries) {
                if (entry==null || !valid(entry.name(),120) || !valid(entry.category(),100) || !valid(entry.keywords(),120)
                        || entry.keywords().replace("|","").isBlank()
                        || entry.priority()<1 || entry.priority()>3 || entry.target()<1 || entry.target()>100)
                    throw new IllegalArgumentException("Invalid grocery type configuration");
                var filters=new LoyaltyHubSource.Filters(entry.departmentFilter(),entry.retailerFilter(),entry.regionFilter(),null);
                jdbc.sql("""
                    INSERT INTO loyalty_generic_types(name,category,keywords,priority,target,department_filter,retailer_filter,region_filter)
                    VALUES (:name,:category,:keywords,:priority,:target,:department,:retailer,:region)
                    ON CONFLICT(name) DO UPDATE SET category=excluded.category,keywords=excluded.keywords,
                        priority=excluded.priority,target=excluded.target,next_run=now(),failures=0,
                        department_filter=excluded.department_filter,retailer_filter=excluded.retailer_filter,region_filter=excluded.region_filter,
                        term_index=CASE WHEN loyalty_generic_types.keywords<>excluded.keywords THEN 0 ELSE loyalty_generic_types.term_index END
                    """).param("name",entry.name().strip()).param("category",entry.category().strip())
                        .param("keywords",entry.keywords().strip()).param("priority",entry.priority())
                        .param("target",entry.target()).param("department",filters.department())
                        .param("retailer",filters.retailer()).param("region",filters.region()).update();
            }
        });
    }

    private static boolean valid(String text,int length) {
        return text!=null && !text.isBlank() && text.length()<=length;
    }

    public Run discoverNext() { return discoverNext(false); }

    private Run discoverNext(boolean refreshOnly) {
        var start=jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        return transactions.execute(tx -> {
            requireLock("LOYALTYHUB");
            if (paused()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Loyalty Hub discovery is paused");
            var next=jdbc.sql(DISCOVERY_SELECT+"""
                 WHERE next_run<=now() AND (NOT :refresh OR EXISTS(SELECT 1 FROM loyalty_generic_variants v WHERE v.generic_id=t.id))
                 ORDER BY
                    ((SELECT count(*) FROM loyalty_generic_variants v WHERE v.generic_id=t.id)>=target),
                    priority, last_run NULLS FIRST, id LIMIT 1
                """).param("refresh",refreshOnly)
                    .query(Discovery.class).optional();
            if (next.isEmpty()) return done("LOYALTYHUB",false,"SKIPPED",new ImportCounts(),start,"No discovery pages are due");
            var job=next.get();
            var terms=java.util.Arrays.stream(job.keywords().split("[|]")).map(String::strip).filter(term -> !term.isBlank()).toList();
            int termIndex=jdbc.sql("SELECT term_index FROM loyalty_generic_types WHERE id=:id")
                    .param("id",job.id()).query(Integer.class).single();
            String term=terms.get(Math.floorMod(termIndex,terms.size()));
            var filters=new LoyaltyHubSource.Filters(job.departmentFilter(),job.retailerFilter(),job.regionFilter(),null);
            var key=(refreshOnly ? "refresh:" : "generic:")+job.id()+":"+java.util.UUID.nameUUIDFromBytes(
                    (term+filters).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var cursor=jdbc.sql("""
                SELECT next_offset,watermark,scan_watermark FROM grocery_price_checkpoints
                WHERE source='LOYALTYHUB' AND search_key=:key
                """).param("key",key).query(Cursor.class).optional().orElse(new Cursor(0,null,null));
            boolean refreshing=refreshOnly || job.variants()>=job.target();
            PriceDataSource.Page page;
            try {
                page=loyaltySource.collect(term,cursor.nextOffset(),
                        !refreshOnly && refreshing && cursor.watermark()!=null ? cursor.watermark().toInstant().minus(Duration.ofDays(1)) : null,
                        refreshing,filters);
            } catch (ResponseStatusException error) {
                updateDiscovery(job.id(),Math.min(1440,15*(1<<Math.min(7,job.failures()))),true);
                return done("LOYALTYHUB",false,"FAILED",new ImportCounts(),start,error.getReason());
            }
            var result=catalogue.accept(page.rows(),false,job.id());
            int offset=page.nextOffset()==null ? 0 : page.nextOffset();
            var window=cursor.scanWatermark()==null ? ScopedPriceIntake.date(page.updatedAt()) : cursor.scanWatermark();
            var watermark=offset==0 && window!=null ? window : cursor.watermark();
            jdbc.sql("""
                INSERT INTO grocery_price_checkpoints(source,search_key,next_offset,watermark,scan_watermark)
                VALUES ('LOYALTYHUB',:key,:offset,:watermark,:window)
                ON CONFLICT(source,search_key) DO UPDATE SET next_offset=excluded.next_offset,
                    watermark=excluded.watermark,scan_watermark=excluded.scan_watermark
                """).param("key",key).param("offset",offset).param("watermark",watermark,Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("window",offset==0 ? null : window,Types.TIMESTAMP_WITH_TIMEZONE).update();
            if (offset==0) jdbc.sql("UPDATE loyalty_generic_types SET term_index=:term WHERE id=:id")
                    .param("term",(termIndex+1)%terms.size()).param("id",job.id()).update();
            updateDiscovery(job.id(),refreshing ? 1440 : 360,false);
            result.checkpoint="offset "+offset;
            return done("LOYALTYHUB",false,"SUCCEEDED",result,start,result.detail());
        });
    }

    private void updateDiscovery(long id,int minutes,boolean failed) {
        jdbc.sql("""
            UPDATE loyalty_generic_types SET next_run=now()+make_interval(mins=>:minutes),
                last_run=CASE WHEN :failed THEN last_run ELSE now() END,
                failures=CASE WHEN :failed THEN failures+1 ELSE 0 END WHERE id=:id
            """).param("id",id).param("minutes",minutes).param("failed",failed).update();
    }

    @Scheduled(cron="${LOYALTYHUB_DISCOVERY_CRON:0 0 */8 * * *}",zone="Africa/Johannesburg")
    public void scheduledDiscovery() {
        if (!loyaltySource.enabled() || paused()) return;
        try { discoverNext(); }
        catch (RuntimeException ex) { log.warn("Loyalty Hub discovery did not complete."); }
    }

    @Scheduled(cron="${LOYALTYHUB_REFRESH_CRON:0 30 16 * * *}",zone="Africa/Johannesburg")
    public void scheduledRefresh() {
        if (!loyaltySource.enabled() || paused()) return;
        try { discoverNext(true); }
        catch (RuntimeException ex) { log.warn("Loyalty Hub price refresh did not complete."); }
    }

    @Scheduled(cron="${LOYALTYHUB_REFERENCE_CRON:0 15 12 * * *}",zone="Africa/Johannesburg")
    public void scheduledReferences() {
        if (!loyaltySource.enabled() || paused()) return;
        for (var kind : LoyaltyHubSource.Reference.values()) {
            try {
                transactions.executeWithoutResult(tx -> {
                    requireLock("LOYALTYHUB");
                    if (paused()) return;
                    boolean due=jdbc.sql("""
                        SELECT NOT EXISTS(SELECT 1 FROM loyalty_reference_sync WHERE kind=:kind AND next_run>now())
                        """).param("kind",kind.name()).query(Boolean.class).single();
                    if (!due) return;
                    var data=loyaltySource.reference(kind);
                    var entries=new java.util.LinkedHashMap<String,String>();
                    for (var item : data) {
                        String identifier=SourceFields.clean(SourceFields.text(item,kind.identifier),100);
                        if (identifier==null && kind==LoyaltyHubSource.Reference.RETAILERS)
                            identifier=SourceFields.clean(SourceFields.text(item,"retailer"),100);
                        if (identifier==null && kind==LoyaltyHubSource.Reference.RETAILERS)
                            identifier=SourceFields.clean(SourceFields.text(item,"slug"),100);
                        String label=SourceFields.clean(SourceFields.text(item,"name"),120);
                        if (identifier!=null) entries.put(identifier,label==null ? identifier : label);
                    }
                    if (entries.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unrecognised Loyalty Hub reference data");
                    jdbc.sql("DELETE FROM loyalty_provider_references WHERE kind=:kind").param("kind",kind.name()).update();
                    entries.forEach((id,label) -> jdbc.sql("""
                        INSERT INTO loyalty_provider_references(kind,identifier,label) VALUES (:kind,:id,:label)
                        """).param("kind",kind.name()).param("id",id).param("label",label).update());
                    jdbc.sql("""
                        INSERT INTO loyalty_reference_sync(kind,next_run) VALUES (:kind,now()+interval '7 days')
                        ON CONFLICT(kind) DO UPDATE SET next_run=excluded.next_run
                        """).param("kind",kind.name()).update();
                });
            } catch (RuntimeException ex) {
                log.warn("Loyalty Hub {} reference update did not complete.",kind);
            }
        }
    }

    public Run filteredLoyalty(String query,LoyaltyHubSource.Filters filters,boolean dry) {
        if ((query==null || query.isBlank()) && filters.department()==null && filters.barcode()==null)
            throw new IllegalArgumentException("Provide a search, department or barcode");
        if (query!=null && query.length()>120) throw new IllegalArgumentException("Search cannot exceed 120 characters");
        return run("LOYALTYHUB",dry,() -> {
            if (paused()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Loyalty Hub ingestion is paused");
            var page=filters.barcode()!=null && filters.retailer()==null && filters.department()==null && (query==null || query.isBlank())
                    ? loyaltySource.barcode(filters.barcode(),filters.region())
                    : loyaltySource.collect(query,0,null,false,filters);
            return catalogue.accept(page.rows(),dry,null);
        });
    }

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
            if ("LOYALTYHUB".equals(source) && paused())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Loyalty Hub ingestion is paused");
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
            jdbc.sql("DELETE FROM loyalty_latest_prices").update();
            jdbc.sql("DELETE FROM loyalty_provider_references").update();
            jdbc.sql("DELETE FROM loyalty_reference_sync").update();
            jdbc.sql("DELETE FROM loyalty_generic_variants").update();
            jdbc.sql("DELETE FROM loyalty_product_identity").update();
            jdbc.sql("UPDATE products SET image_url=NULL,image_source_url=NULL WHERE image_source_url='https://loyaltyhub.co.za/'").update();
            jdbc.sql("DELETE FROM loyalty_product_metadata").update();
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
