package za.co.shoppingdiary.ingestion.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class LoyaltyHubQuota {
    private final JdbcClient jdbc;

    @Value("${LOYALTYHUB_MONTHLY_REQUESTS:100}") private int monthlyRequests;
    @Value("${LOYALTYHUB_MONTHLY_ROWS:1000}") private int monthlyRows;
    @Value("${LOYALTYHUB_REFRESH_REQUEST_RESERVE:10}") private int refreshReserve;
    @Value("${LOYALTYHUB_DAILY_REQUESTS:3}") private int dailyRequests;

    public record Usage(int localCalls,Integer remaining,Integer rowsRemaining,OffsetDateTime retryAfter,
                        OffsetDateTime quotaReset,OffsetDateTime lastCheckedAt,int localRows) {}
    private record Limit(Integer remaining,Integer rowsRemaining,OffsetDateTime retryAfter,OffsetDateTime quotaReset) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public long reserve(boolean refresh,int pageSize) {
        var state=jdbc.sql("""
            SELECT remaining,rows_remaining,retry_after,quota_reset FROM loyalty_hub_free_state WHERE singleton FOR UPDATE
            """).query(Limit.class).single();
        var now=Instant.now();
        boolean reset=state.quotaReset()!=null && !state.quotaReset().toInstant().isAfter(now);
        int reserve=refresh ? 0 : Math.max(0,refreshReserve);
        var usage=usage();
        int day=jdbc.sql("SELECT count(*) FROM loyalty_hub_free_requests WHERE requested_at>=now()-interval '1 day'")
                .query(Integer.class).single();
        int minute=jdbc.sql("SELECT count(*) FROM loyalty_hub_free_requests WHERE requested_at>=now()-interval '1 minute'")
                .query(Integer.class).single();
        if (usage.localCalls()>=Math.min(100,monthlyRequests)-reserve
                || usage.localRows()+pageSize>Math.min(1000,monthlyRows)-reserve*10
                || day>=dailyRequests || minute>=20
                || (state.retryAfter()!=null && state.retryAfter().toInstant().isAfter(now))
                || (!reset && ((state.remaining()!=null && state.remaining()<=reserve)
                || (state.rowsRemaining()!=null && state.rowsRemaining()<pageSize+reserve*10)))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Loyalty Hub quota or cooldown reached");
        }
        return jdbc.sql("INSERT INTO loyalty_hub_free_requests(rows_used) VALUES (:rows) RETURNING id")
                .param("rows",pageSize).query(Long.class).single();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void delivered(long id,int rows) {
        jdbc.sql("UPDATE loyalty_hub_free_requests SET rows_used=:rows WHERE id=:id AND rows_used>=:rows")
                .param("rows",rows).param("id",id).update();
    }

    public Usage usage() {
        return jdbc.sql("""
            SELECT totals.local_calls,remaining,rows_remaining,retry_after,quota_reset,last_checked_at,totals.local_rows
            FROM loyalty_hub_free_state CROSS JOIN LATERAL (
                SELECT count(*) AS local_calls,coalesce(sum(rows_used),0) AS local_rows
                FROM loyalty_hub_free_requests
                WHERE requested_at >= date_trunc('month',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
            ) totals WHERE singleton
            """).query(Usage.class).single();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(Integer remaining,Integer rows,Instant cooldown,Instant reset) {
        jdbc.sql("""
            UPDATE loyalty_hub_free_state SET remaining=coalesce(:remaining,remaining),rows_remaining=coalesce(:rows,rows_remaining),
              retry_after=coalesce(:cooldown,retry_after),quota_reset=coalesce(:reset,quota_reset),last_checked_at=now()
            WHERE singleton
            """).param("remaining",remaining,Types.INTEGER).param("rows",rows,Types.INTEGER)
                .param("cooldown",ScopedPriceIntake.date(cooldown),Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reset",ScopedPriceIntake.date(reset),Types.TIMESTAMP_WITH_TIMEZONE).update();
    }
}
