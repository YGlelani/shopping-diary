package za.co.shoppingdiary.list.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.auth.UserPrincipal;
import za.co.shoppingdiary.list.service.ShoppingPlanService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shopping-plans")
@RequiredArgsConstructor
public class ShoppingPlanController {
    private final ShoppingPlanService service;

    public record Created(long id) {}
    public record Copy(@NotBlank @Size(max=200) String name) {}

    @GetMapping
    public List<ShoppingPlanService.Summary> history(@AuthenticationPrincipal UserPrincipal user,
                                                      @RequestParam(defaultValue="0") int offset) {
        return service.history(user.getId(),offset);
    }

    @GetMapping("/{id}")
    public ShoppingPlanService.Snapshot detail(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id) {
        return service.detail(user.getId(),id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created save(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody ShoppingPlanService.Save input,
                         @RequestHeader(name="Idempotency-Key",required=false) UUID key) {
        return new Created(service.save(user.getId(),input,requiredKey(key)));
    }

    @PostMapping("/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public Created copy(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@Valid @RequestBody Copy input,
                         @RequestHeader(name="Idempotency-Key",required=false) UUID key) {
        return new Created(service.copy(user.getId(),id,input.name(),requiredKey(key)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id) {
        service.delete(user.getId(),id);
    }

    private static UUID requiredKey(UUID key) {
        if (key==null) throw new IllegalArgumentException("An Idempotency-Key UUID is required");
        return key;
    }
}
