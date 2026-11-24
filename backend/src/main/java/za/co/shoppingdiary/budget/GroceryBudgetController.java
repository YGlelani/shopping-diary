package za.co.shoppingdiary.budget;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.auth.UserPrincipal;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
public class GroceryBudgetController {
    private final GroceryBudgetService service;

    public record Created(long id) {}
    public record Reuse(@NotBlank @Size(max = 200) String name, @NotNull LocalDate startsOn, LocalDate endsOn) {}

    @GetMapping
    public List<GroceryBudgetService.Overview> overview(@AuthenticationPrincipal UserPrincipal me,
            @RequestParam(defaultValue = "0") int offset) {
        return service.overview(me.getId(), offset);
    }

    @GetMapping("/{id}")
    public GroceryBudgetService.Detail detail(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id,
            @RequestParam(defaultValue = "0") int offset) {
        return service.detail(me.getId(), id, offset);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@AuthenticationPrincipal UserPrincipal me, @Valid @RequestBody GroceryBudgetService.Input input,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID key) {
        return new Created(service.create(me.getId(), input, key));
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id, @RequestParam long revision,
            @Valid @RequestBody GroceryBudgetService.Input input) {
        service.update(me.getId(), id, revision, input);
    }

    @PostMapping("/{id}/reuse")
    @ResponseStatus(HttpStatus.CREATED)
    public Created reuse(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id, @Valid @RequestBody Reuse input,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID key) {
        return new Created(service.reuse(me.getId(), id, input.name(), input.startsOn(), input.endsOn(), key));
    }

    @GetMapping("/{id}/estimate")
    public GroceryBudgetService.Estimate estimate(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lon,
            @RequestParam(defaultValue = "15") double r, @RequestParam(required = false) Long branchId,
            @RequestParam(name="loyaltyRetailer",required=false) Set<Long> loyalty) {
        return service.estimate(me.getId(), id, lat, lon, r, branchId,loyalty==null ? Set.of() : loyalty);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id, @RequestParam long revision,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lon,
            @RequestParam(defaultValue = "15") double r, @RequestParam(required = false) Long branchId,
            @RequestParam(name="loyaltyRetailer",required=false) Set<Long> loyalty) {
        service.archive(me.getId(), id, revision, lat, lon, r, branchId,loyalty==null ? Set.of() : loyalty);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id, @RequestParam long revision) {
        service.delete(me.getId(), id, revision);
    }

    @PostMapping("/{id}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public Created record(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id,
            @Valid @RequestBody GroceryBudgetService.ExpenseInput input, @RequestHeader("Idempotency-Key") UUID key) {
        return new Created(service.record(me.getId(), id, input, key));
    }

    @PutMapping("/{id}/expenses/{expense}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void correct(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id, @PathVariable long expense,
            @RequestParam long revision, @Valid @RequestBody GroceryBudgetService.ExpenseInput input) {
        service.correct(me.getId(), id, expense, revision, input);
    }

    @DeleteMapping("/{id}/expenses/{expense}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id, @PathVariable long expense,
            @RequestParam long revision) {
        service.removeExpense(me.getId(), id, expense, revision);
    }
}
