package za.co.shoppingdiary.budget;

import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import za.co.shoppingdiary.list.service.ShoppingEstimateService;
import za.co.shoppingdiary.list.service.ShoppingEstimateService.Line;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GroceryBudgetService {
    private static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");
    private final JdbcClient jdbc;
    private final ShoppingEstimateService estimates;

    public record Input(@NotBlank @Size(max = 200) String name, @Size(max = 1000) String description,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @NotNull Period period, @NotNull LocalDate startsOn, LocalDate endsOn,
            @NotNull @Size(max = 30) List<@NotNull @Positive Long> listIds) {}
    public enum Period { TRIP, WEEKLY, FORTNIGHTLY, MONTHLY, CUSTOM }
    public record ExpenseInput(@NotBlank @Size(max = 300) String description, @Size(max = 100) String category,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @NotNull LocalDate purchasedOn, @Positive Long listId, @Positive Long branchId) {}
    public record Budget(long id, String name, String description, BigDecimal amount, Period period, LocalDate startsOn,
            LocalDate endsOn, String status, long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            BigDecimal snapshotSubtotal, Integer snapshotEntries, Integer snapshotPriced, OffsetDateTime snapshotAt,
            Boolean snapshotSingleBranch) {}
    public record Overview(long id, String name, BigDecimal amount, Period period, LocalDate startsOn, LocalDate endsOn,
            String status, BigDecimal actualSpent, BigDecimal actualRemaining, int recordedExpenses,
            BigDecimal snapshotSubtotal, Integer snapshotEntries, Integer snapshotPriced) {}
    public record Expense(long id, long budgetId, Long listId, Long branchId, String branchName, String description,
            String category, BigDecimal amount, LocalDate purchasedOn, long revision) {}
    public record CategoryTotal(String category, BigDecimal total) {}
    public record Detail(Budget budget, List<Long> listIds, BigDecimal actualSpent, BigDecimal actualRemaining,
                         List<Expense> expenses, long expenseCount, List<CategoryTotal> spendingByCategory) {}
    public record Estimate(BudgetAssessment assessment, List<Line> lines, OffsetDateTime calculatedAt,
                           Long branchId, boolean singleBranch, boolean snapshot, BigDecimal recordedDifference) {}

    private static final String EXPENSE_FIELDS = """
        SELECT e.id, e.budget_id, e.list_id, e.branch_id, s.name AS branch_name,
               e.description, e.category, e.amount, e.purchased_on, e.revision
        FROM grocery_expenses e LEFT JOIN stores s ON s.id = e.branch_id
        """;

    public record AvailableFunds(long id,String name,BigDecimal remaining) {}

    @Transactional(readOnly = true)
    public List<AvailableFunds> availableFunds(long owner,long list,Long id) {
        return jdbc.sql("""
            SELECT b.id,b.name,b.amount-coalesce((SELECT sum(amount) FROM grocery_expenses WHERE budget_id=b.id),0) AS remaining
            FROM grocery_budgets b JOIN grocery_budget_lists l ON l.budget_id=b.id
            WHERE b.user_id=:owner AND l.list_id=:list AND b.status='ACTIVE'
              AND (:id IS NULL OR b.id=:id)
              AND (CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Johannesburg')::date BETWEEN b.starts_on AND b.ends_on
            ORDER BY b.id DESC
            """).param("owner",owner).param("list",list).param("id",id,Types.BIGINT).query(AvailableFunds.class).list();
    }

    @Transactional(readOnly = true)
    public List<Overview> overview(long userId, int offset) {
        page(offset);
        return jdbc.sql("""
            SELECT b.id,b.name,b.amount,b.period,b.starts_on,b.ends_on,b.status,
                totals.spent AS actual_spent,b.amount-totals.spent AS actual_remaining,totals.entries AS recorded_expenses,
                b.snapshot_subtotal,b.snapshot_entries,b.snapshot_priced
            FROM grocery_budgets b
            CROSS JOIN LATERAL (SELECT coalesce(sum(amount),0) AS spent,count(*) AS entries
                               FROM grocery_expenses WHERE budget_id=b.id) totals
            WHERE b.user_id=:owner ORDER BY b.id DESC LIMIT 50 OFFSET :offset
            """).param("owner",userId).param("offset",offset).query(Overview.class).list();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(long owner, long id, int offset) {
        var budget = owned(owner,id,false);
        page(offset);
        var expenses = jdbc.sql(EXPENSE_FIELDS + """
             WHERE e.budget_id=:id ORDER BY e.purchased_on DESC,e.id DESC LIMIT 25 OFFSET :offset
            """).param("id",id).param("offset",offset).query(Expense.class).list();
        var categories = jdbc.sql("""
            SELECT coalesce(category,'Uncategorised') AS category,sum(amount) AS total
            FROM grocery_expenses WHERE budget_id=:id GROUP BY 1 ORDER BY total DESC,category
            """).param("id",id).query(CategoryTotal.class).list();
        var spent = categories.stream().map(CategoryTotal::total).reduce(BigDecimal.ZERO,BigDecimal::add);
        long count = jdbc.sql("SELECT count(*) FROM grocery_expenses WHERE budget_id=:id").param("id",id)
                .query(Long.class).single();
        return new Detail(budget, links(id), spent, budget.amount().subtract(spent), expenses, count, categories);
    }

    @Transactional
    public long create(long owner, Input input, UUID key) {
        if (key != null) {
            jdbc.sql("SELECT id FROM users WHERE id=:owner FOR UPDATE").param("owner",owner).query(Long.class).single();
            var existing = jdbc.sql("SELECT id FROM grocery_budgets WHERE user_id=:owner AND request_id=:key")
                    .param("owner",owner).param("key",key).query(Long.class).optional();
            if (existing.isPresent()) return existing.get();
        }
        var end = endDate(input);
        var listIds = ownedLists(owner,input.listIds());
        long id = jdbc.sql("""
            INSERT INTO grocery_budgets (user_id,name,description,amount,period,starts_on,ends_on,request_id)
            VALUES (:owner,:name,:description,:amount,:period,:starts,:ends,:key) RETURNING id
            """).paramSource(inputParams(owner,input,end).addValue("key",key)).query(Long.class).single();
        replaceLinks(owner,id,listIds);
        return id;
    }

    @Transactional
    public long reuse(long owner, long id, String name, LocalDate starts, LocalDate ends, UUID key) {
        var original = owned(owner,id,false);
        return create(owner,new Input(name,original.description(),original.amount(),original.period(),starts,ends,links(id)),key);
    }

    @Transactional
    public void update(long owner, long id, long revision, Input input) {
        var budget = owned(owner,id,true);
        checkRevision(budget.revision(),revision);
        active(budget);
        var end = endDate(input);
        var listIds = ownedLists(owner,input.listIds());
        if (jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM grocery_expenses
                WHERE budget_id=:id AND purchased_on NOT BETWEEN :starts AND :ends)
            """).param("id",id).param("starts",input.startsOn()).param("ends",end).query(Boolean.class).single()) {
            throw new IllegalArgumentException("The budget dates must still include its recorded purchases");
        }
        jdbc.sql("""
            UPDATE grocery_budgets SET name=:name,description=:description,amount=:amount,period=:period,
              starts_on=:starts,ends_on=:ends,updated_at=now(),revision=revision+1 WHERE id=:id
            """).paramSource(inputParams(owner,input,end).addValue("id",id)).update();
        replaceLinks(owner,id,listIds);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Estimate estimate(long owner,long id,Double lat,Double lon,double radius,Long branch) {
        return estimate(owned(owner,id,false),owner,lat,lon,radius,branch);
    }

    private Estimate estimate(Budget budget,long owner,Double lat,Double lon,double radius,Long branch) {
        if ("ARCHIVED".equals(budget.status())) {
            if (budget.snapshotAt() == null) return new Estimate(null,List.of(),null,null,false,true,null);
            boolean single = Boolean.TRUE.equals(budget.snapshotSingleBranch());
            var assessment = BudgetAssessment.calculate(budget.amount(),budget.snapshotSubtotal(),
                    budget.snapshotEntries(),budget.snapshotPriced(),single);
            return new Estimate(assessment,List.of(),budget.snapshotAt(),null,single,true,recordedDifference(budget.id(),assessment));
        }
        var lines = estimates.estimate(owner,links(budget.id()),lat,lon,radius,branch,true);
        var priced = lines.stream().filter(line -> line.total() != null).toList();
        var subtotal = priced.stream().map(Line::total).reduce(BigDecimal.ZERO,BigDecimal::add);
        boolean single = !priced.isEmpty() && priced.stream().map(Line::storeId).distinct().count() == 1;
        var assessment = BudgetAssessment.calculate(budget.amount(),subtotal,lines.size(),priced.size(),single);
        return new Estimate(assessment,lines,OffsetDateTime.now(),branch,single,false,recordedDifference(budget.id(),assessment));
    }

    private BigDecimal recordedDifference(long id, BudgetAssessment assessment) {
        if (assessment.knownSubtotal() == null || assessment.unpriced() > 0) return null;
        return jdbc.sql("SELECT sum(amount) FROM grocery_expenses WHERE budget_id=:id").param("id",id)
                .query(BigDecimal.class).optional().map(total -> total.subtract(assessment.knownSubtotal())).orElse(null);
    }

    @Transactional
    public void archive(long owner,long id,long revision,Double lat,Double lon,double radius,Long branch) {
        var budget = owned(owner,id,true);
        checkRevision(budget.revision(),revision);
        active(budget);
        var quote = estimate(budget,owner,lat,lon,radius,branch);
        var assessment = quote.assessment();
        jdbc.sql("""
            UPDATE grocery_budgets SET status='ARCHIVED',snapshot_subtotal=:subtotal,snapshot_entries=:entries,
                snapshot_priced=:priced,snapshot_at=:at,snapshot_single_branch=:single,revision=revision+1,updated_at=now()
            WHERE id=:id
            """).param("subtotal",assessment.knownSubtotal(),Types.NUMERIC).param("entries",assessment.entries())
                .param("priced",assessment.priced()).param("at",quote.calculatedAt()).param("single",quote.singleBranch()).param("id",id).update();
    }

    @Transactional
    public void delete(long owner,long id,long revision) {
        checkRevision(owned(owner,id,true).revision(),revision);
        jdbc.sql("DELETE FROM grocery_budgets WHERE id=:id").param("id",id).update();
    }

    @Transactional
    public long record(long owner,long budgetId,ExpenseInput input,UUID key) {
        var budget = owned(owner,budgetId,true);
        var existing = jdbc.sql("SELECT id,budget_id FROM grocery_expenses WHERE user_id=:owner AND request_id=:key")
                .param("owner",owner).param("key",key).query(ReceiptKey.class).optional();
        if (existing.isPresent()) {
            if (existing.get().budgetId() != budgetId) conflict("This purchase request already belongs to another budget");
            return existing.get().id();
        }
        validateExpense(budget,input,null);
        return jdbc.sql("""
            INSERT INTO grocery_expenses (budget_id,user_id,list_id,branch_id,amount,purchased_on,description,category,request_id)
            VALUES (:id,:owner,:list,:branch,:amount,:date,:description,:category,:key)
            ON CONFLICT (user_id,request_id) DO UPDATE SET request_id=excluded.request_id
            WHERE grocery_expenses.budget_id=excluded.budget_id
            RETURNING id
            """).paramSource(expenseParams(budgetId,input).addValue("owner",owner).addValue("key",key))
                .query(Long.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"Purchase request is already used"));
    }

    private record ReceiptKey(long id,long budgetId) {}

    @Transactional
    public void correct(long owner,long id,long expense,long revision,ExpenseInput input) {
        var budget = owned(owner,id,true);
        var previous = expense(id,expense);
        checkRevision(previous.revision(),revision);
        validateExpense(budget,input,previous.listId());
        jdbc.sql("""
            UPDATE grocery_expenses SET list_id=:list,branch_id=:branch,amount=:amount,purchased_on=:date,
              description=:description,category=:category,revision=revision+1,updated_at=now()
            WHERE id=:expense AND budget_id=:id
            """).paramSource(expenseParams(id,input).addValue("expense",expense)).update();
    }

    @Transactional
    public void removeExpense(long owner,long id,long expense,long revision) {
        owned(owner,id,true);
        checkRevision(expense(id,expense).revision(),revision);
        jdbc.sql("DELETE FROM grocery_expenses WHERE id=:expense AND budget_id=:id")
                .param("expense",expense).param("id",id).update();
    }

    private Expense expense(long id,long expense) {
        return jdbc.sql(EXPENSE_FIELDS+" WHERE e.id=:expense AND e.budget_id=:id")
                .param("expense",expense).param("id",id).query(Expense.class)
                .optional().orElseThrow(() -> missing("Purchase not found"));
    }

    private void validateExpense(Budget budget,ExpenseInput input,Long previousList) {
        if (input.purchasedOn().isBefore(budget.startsOn()) || input.purchasedOn().isAfter(budget.endsOn())
                || input.purchasedOn().isAfter(LocalDate.now(ZONE))) {
            throw new IllegalArgumentException("Purchase date must be within the budget period and no later than today");
        }
        if (input.listId()!=null && !Objects.equals(previousList,input.listId()) && !links(budget.id()).contains(input.listId())) {
            throw new IllegalArgumentException("Choose a shopping list linked to this budget");
        }
        if (input.branchId()!=null && !jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM stores WHERE id=:branch AND upper(store_code) IS DISTINCT FROM 'ONLINE')
            """).param("branch",input.branchId()).query(Boolean.class).single()) {
            throw new IllegalArgumentException("Unknown physical branch");
        }
    }

    private Budget owned(long owner,long id,boolean lock) {
        return jdbc.sql("SELECT * FROM grocery_budgets WHERE id=:id AND user_id=:owner"+(lock?" FOR UPDATE":""))
                .param("owner",owner).param("id",id).query(Budget.class)
                .optional().orElseThrow(() -> missing("Budget not found"));
    }

    private List<Long> links(long id) {
        return jdbc.sql("SELECT list_id FROM grocery_budget_lists WHERE budget_id=:id ORDER BY list_id")
                .param("id",id).query(Long.class).list();
    }

    private List<Long> ownedLists(long owner,List<Long> requested) {
        var ids = requested.stream().distinct().toList();
        if (!ids.isEmpty()) {
            var count = jdbc.sql("SELECT count(*) FROM shopping_lists WHERE user_id=:owner AND id IN (:ids)")
                    .param("owner",owner).param("ids",ids).query(Long.class).single();
            if (count != ids.size()) throw missing("Shopping list not found");
        }
        return ids;
    }

    private void replaceLinks(long owner,long id,List<Long> ids) {
        jdbc.sql("DELETE FROM grocery_budget_lists WHERE budget_id=:id").param("id",id).update();
        if (!ids.isEmpty()) {
            jdbc.sql("""
                INSERT INTO grocery_budget_lists (budget_id,list_id,user_id)
                SELECT :id,l.id,l.user_id FROM shopping_lists l WHERE l.user_id=:owner AND l.id IN (:ids)
                """).param("id",id).param("owner",owner).param("ids",ids).update();
        }
    }

    private static LocalDate endDate(Input input) {
        if (input.startsOn().getYear() < 1900 || input.startsOn().getYear() > 9998
                || (input.endsOn()!=null && input.endsOn().getYear()>9998)) {
            throw new IllegalArgumentException("Choose dates between 1900 and 9998");
        }
        LocalDate end = switch (input.period()) {
            case TRIP -> input.startsOn();
            case WEEKLY -> input.startsOn().plusDays(6);
            case FORTNIGHTLY -> input.startsOn().plusDays(13);
            case MONTHLY -> input.startsOn().plusMonths(1).minusDays(1);
            case CUSTOM -> input.endsOn();
        };
        if (end == null || end.isBefore(input.startsOn())) throw new IllegalArgumentException("Choose a valid end date");
        return end;
    }

    private static MapSqlParameterSource inputParams(long owner,Input input,LocalDate end) {
        return new MapSqlParameterSource("owner",owner).addValue("name",input.name().strip())
                .addValue("description",clean(input.description())).addValue("amount",input.amount())
                .addValue("period",input.period().name()).addValue("starts",input.startsOn()).addValue("ends",end);
    }

    private static MapSqlParameterSource expenseParams(long id,ExpenseInput input) {
        return new MapSqlParameterSource("id",id).addValue("list",input.listId(),Types.BIGINT)
                .addValue("branch",input.branchId(),Types.BIGINT).addValue("amount",input.amount())
                .addValue("date",input.purchasedOn()).addValue("description",input.description().strip())
                .addValue("category",clean(input.category()));
    }

    private static String clean(String text) { return text == null || text.isBlank() ? null : text.strip(); }
    private static void page(int offset) { if (offset < 0 || offset > 10000) throw new IllegalArgumentException("Invalid page"); }
    private static void active(Budget budget) { if ("ARCHIVED".equals(budget.status())) conflict("This budget is archived"); }
    private static void checkRevision(long saved,long supplied) { if (saved!=supplied) conflict("This record changed. Reload before saving."); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private static ResponseStatusException missing(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND,message); }
}
