package za.co.shoppingdiary.ingestion.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class LoyaltyHubQuota {
    private final JdbcClient jdbc;

    public record Usage(int localCalls,Integer remaining,Integer rowsRemaining,OffsetDateTime retryAfter,OffsetDateTime quotaReset,
                        OffsetDateTime lastCheckedAt) {}
    private record Limit(Integer remaining,Integer rowsRemaining,OffsetDateTime retryAfter,OffsetDateTime quotaReset) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reserve() {
        var state = jdbc.sql("""
            SELECT remaining,rows_remaining,retry_after,quota_reset FROM loyalty_hub_free_state WHERE singleton FOR UPDATE
            """).query(Limit.class).single();
        var now=Instant.now();
        boolean reset=state.quotaReset()!=null && !state.quotaReset().toInstant().isAfter(now);
        int month=count(31);
        int day=count(1);
        if (month>=100 || day>=3 || (state.retryAfter()!=null && state.retryAfter().toInstant().isAfter(now))
                || (!reset && ((state.remaining()!=null && state.remaining()<1) ||
                    (state.rowsRemaining()!=null && state.rowsRemaining()<10)))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Loyalty Hub free-tier quota or cooldown reached");
        }
        jdbc.sql("INSERT INTO loyalty_hub_free_requests DEFAULT VALUES").update();
        jdbc.sql("DELETE FROM loyalty_hub_free_requests WHERE requested_at<now()-interval '45 days'").update();
    }

    private int count(int days) {
        return jdbc.sql("SELECT count(*) FROM loyalty_hub_free_requests WHERE requested_at>=now()-make_interval(days=>:days)")
                .param("days",days).query(Integer.class).single();
    }

    public Usage usage() {
        return jdbc.sql("""
            SELECT (SELECT count(*) FROM loyalty_hub_free_requests WHERE requested_at>=now()-interval '31 days') AS local_calls,
              remaining,rows_remaining,retry_after,quota_reset,last_checked_at FROM loyalty_hub_free_state WHERE singleton
            """).query(Usage.class).single();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(Integer remaining,Integer rows,Instant cooldown,Instant reset) {
        jdbc.sql("""
            UPDATE loyalty_hub_free_state SET remaining=coalesce(:remaining,remaining),rows_remaining=coalesce(:rows,rows_remaining),
              retry_after=coalesce(:cooldown,retry_after),quota_reset=coalesce(:reset,quota_reset),last_checked_at=now()
            WHERE singleton
            """).param("remaining",remaining,Types.INTEGER).param("rows",rows,Types.INTEGER)
                .param("cooldown",date(cooldown),Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reset",date(reset),Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    private static OffsetDateTime date(Instant source) {
        return source==null ? null : source.atOffset(ZoneOffset.UTC);
    }
}
