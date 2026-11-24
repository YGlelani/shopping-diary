package za.co.shoppingdiary.list.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import za.co.shoppingdiary.list.dto.OptimizationPlan;
import za.co.shoppingdiary.list.dto.OptimizeRequest;
import za.co.shoppingdiary.list.dto.OptimizeResponse;

@Service
@RequiredArgsConstructor
public class ShoppingPlanService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final BasketOptimizerService optimizer;

    public record Save(@Positive long listId,@NotBlank @Size(max=200) String name,
                       @NotNull Strategy strategy,@Pattern(regexp="[a-f0-9]{64}") @NotNull String fingerprint,
                       @NotNull @Valid OptimizeRequest assumptions) {}
    public enum Strategy { ONE_STORE,SPLIT,ALTERNATIVES }
    public record Summary(long id, Long listId,String listName,String name,String strategy,OffsetDateTime calculatedAt,
                          OffsetDateTime createdAt) {}
    private record Stored(long id,Long listId,String listName,String name,String strategy,OffsetDateTime calculatedAt,
                          OffsetDateTime createdAt,String assumptions,String estimate) {}
    public record Snapshot(Summary summary,JsonNode assumptions,JsonNode estimate) {}

    public OptimizeResponse preview(long owner,long list,OptimizeRequest request) {
        var result = optimizer.optimize(owner,list,request);
        for (var strategy : Strategy.values()) {
            var plan = selected(result,strategy);
            if (!plan.getItems().isEmpty()) plan.setFingerprint(fingerprint(plan,result));
        }
        return result;
    }

    @Transactional
    public long save(long user,Save input,UUID key) {
        jdbc.sql("SELECT id FROM users WHERE id=:owner FOR UPDATE").param("owner",user).query(Long.class).single();
        var previous = existing(user,key);
        if (previous.isPresent()) return previous.get();
        var quote = optimizer.optimize(user,input.listId(),input.assumptions());
        var plan = selected(quote,input.strategy());
        if (plan.getItems().isEmpty() || !fingerprint(plan,quote).equals(input.fingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"The list, budget or eligible prices changed. Recalculate before saving.");
        }
        return jdbc.sql("""
            INSERT INTO shopping_plan_snapshots(user_id,list_id,list_name,name,strategy,assumptions,estimate,calculated_at,request_id)
            SELECT :owner,l.id,l.name,:name,:strategy,CAST(:assumptions AS jsonb),CAST(:estimate AS jsonb),:at,:key
            FROM shopping_lists l WHERE l.id=:list AND l.user_id=:owner
            ON CONFLICT (user_id,request_id) DO UPDATE SET request_id=excluded.request_id
            RETURNING id
            """).param("owner",user).param("list",input.listId()).param("name",input.name().strip())
                .param("strategy",input.strategy().name()).param("assumptions",json.writeValueAsString(input.assumptions()))
                .param("estimate",json.writeValueAsString(plan)).param("at",quote.getCalculatedAt()).param("key",key)
                .query(Long.class).optional().orElseThrow(ShoppingPlanService::missing);
    }

    public List<Summary> history(long owner,int offset) {
        if (offset<0 || offset>10000) throw new IllegalArgumentException("Invalid plan page");
        return jdbc.sql("""
            SELECT id,list_id,list_name,name,strategy,calculated_at,created_at FROM shopping_plan_snapshots
            WHERE user_id=:owner ORDER BY id DESC LIMIT 25 OFFSET :offset
            """).param("owner",owner).param("offset",offset).query(Summary.class).list();
    }

    public Snapshot detail(long owner,long id) {
        var row = jdbc.sql("""
            SELECT id,list_id,list_name,name,strategy,calculated_at,created_at,assumptions::text,estimate::text
            FROM shopping_plan_snapshots WHERE id=:id AND user_id=:owner
            """).param("owner",owner).param("id",id).query(Stored.class).optional().orElseThrow(ShoppingPlanService::missing);
        return new Snapshot(new Summary(row.id(),row.listId(),row.listName(),row.name(),row.strategy(),row.calculatedAt(),row.createdAt()),
                json.readTree(row.assumptions()),json.readTree(row.estimate()));
    }

    @Transactional
    public long copy(long owner,long id,String name,UUID key) {
        var previous = existing(owner,key);
        if (previous.isPresent()) return previous.get();
        return jdbc.sql("""
            INSERT INTO shopping_plan_snapshots(user_id,list_id,list_name,name,strategy,assumptions,estimate,calculated_at,request_id)
            SELECT user_id,list_id,list_name,:name,strategy,assumptions,estimate,calculated_at,:key
            FROM shopping_plan_snapshots WHERE id=:id AND user_id=:owner
            ON CONFLICT (user_id,request_id) DO UPDATE SET request_id=excluded.request_id RETURNING id
            """).param("owner",owner).param("id",id).param("name",name.strip()).param("key",key)
                .query(Long.class).optional().orElseThrow(ShoppingPlanService::missing);
    }

    public void delete(long owner,long id) {
        int deleted = jdbc.sql("DELETE FROM shopping_plan_snapshots WHERE id=:id AND user_id=:owner")
                .param("owner",owner).param("id",id).update();
        if (deleted==0) throw missing();
    }

    private java.util.Optional<Long> existing(long owner,UUID key) {
        return jdbc.sql("SELECT id FROM shopping_plan_snapshots WHERE user_id=:owner AND request_id=:key")
                .param("owner",owner).param("key",key).query(Long.class).optional();
    }

    private static OptimizationPlan selected(OptimizeResponse response,Strategy strategy) {
        return switch (strategy) {
            case ONE_STORE -> response.getBestSingleStore();
            case SPLIT -> response.getBestSplit();
            case ALTERNATIVES -> response.getBestAlternatives();
        };
    }

    private String fingerprint(OptimizationPlan plan,OptimizeResponse quote) {
        // The recommendation may share the same plan instance as a strategy card.
        var fingerprint = plan.getFingerprint();
        plan.setFingerprint(null);
        String data = json.writeValueAsString(plan);
        plan.setFingerprint(fingerprint);
        String signed = quote.getListRevision() + "|" + quote.getBudgetAmount() + "|" + data;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Shopping plan not found");
    }
}
