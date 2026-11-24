package za.co.shoppingdiary.list.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.auth.UserPrincipal;
import za.co.shoppingdiary.list.dto.*;
import za.co.shoppingdiary.list.service.BasketOptimizerService;
import za.co.shoppingdiary.list.service.ShoppingListService;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/lists")
@RequiredArgsConstructor
public class ShoppingListController {

    private final ShoppingListService service;
    private final BasketOptimizerService optimizerService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListDetailResponse create(@AuthenticationPrincipal UserPrincipal me,
                                     @Valid @RequestBody CreateListRequest req,
                                     @RequestHeader(name = "Idempotency-Key", required = false) UUID requestId) {
        return service.create(me.getId(), req, requestId);
    }

    @GetMapping
    public List<ListDetailResponse> mine(@AuthenticationPrincipal UserPrincipal me) {
        return service.mine(me.getId());
    }

    @GetMapping("/{id}")
    public ListDetailResponse detail(@AuthenticationPrincipal UserPrincipal me,
                                     @PathVariable Long id) {
        return service.detail(me.getId(), id);
    }

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ListDetailResponse.Item addItem(@AuthenticationPrincipal UserPrincipal me,
                                           @PathVariable Long id,
                                           @Valid @RequestBody AddItemRequest req,
                                           @RequestHeader(name = "Idempotency-Key", required = false) UUID requestId) {
        return service.addItem(me.getId(), id, req, requestId);
    }

    @PostMapping("/{id}/items/{itemId}/increment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void incrementItem(@AuthenticationPrincipal UserPrincipal me,
                              @PathVariable Long id, @PathVariable Long itemId) {
        service.incrementItem(me.getId(), id, itemId);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@AuthenticationPrincipal UserPrincipal me,
                           @PathVariable Long id,
                           @PathVariable Long itemId, @RequestParam(required = false) Long revision) {
        service.removeItem(me.getId(), id, itemId, revision);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal me,
                       @PathVariable Long id, @RequestParam(required = false) Long revision) {
        service.delete(me.getId(), id, revision);
    }

    @PutMapping("/{id}")
    public ListDetailResponse update(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id,
            @RequestParam long revision, @RequestParam(required = false) String status,
            @Valid @RequestBody CreateListRequest req) {
        return service.update(me.getId(), id, req, revision, status);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public ListDetailResponse duplicate(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id,
                                        @Valid @RequestBody CopyRequest req,
                                        @RequestHeader(name = "Idempotency-Key", required = false) UUID requestId) {
        return service.duplicate(me.getId(), id, req.name(), requestId);
    }

    public record CopyRequest(@NotBlank @Size(max = 200) String name) {}

    @PutMapping("/{id}/items/{itemId}")
    public ListDetailResponse updateItem(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id,
            @PathVariable long itemId, @RequestParam long revision, @Valid @RequestBody AddItemRequest req) {
        return service.updateItem(me.getId(), id, itemId, req, revision);
    }

    @DeleteMapping("/{id}/items/purchased")
    public ListDetailResponse clearPurchased(@AuthenticationPrincipal UserPrincipal me, @PathVariable long id,
                                             @RequestParam long revision) {
        return service.clearPurchased(me.getId(), id, revision);
    }

    @GetMapping("/coverage")
    public List<ShoppingListService.ListCoverage> coverage(@AuthenticationPrincipal UserPrincipal me,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lon,
            @RequestParam(defaultValue = "15") double r) {
        return service.coverage(me.getId(), lat, lon, r);
    }

    @GetMapping("/{id}/estimate")
    public List<ShoppingListService.ItemEstimate> estimate(@AuthenticationPrincipal UserPrincipal me,
            @PathVariable long id, @RequestParam double lat, @RequestParam double lon,
            @RequestParam(defaultValue = "15") double r) {
        return service.estimate(me.getId(), id, lat, lon, r);
    }

    @GetMapping("/{id}/baskets")
    public List<BasketRow> baskets(@AuthenticationPrincipal UserPrincipal me,
                                   @PathVariable Long id,
                                   @RequestParam double lat,
                                   @RequestParam double lon,
                                   @RequestParam(value = "r", defaultValue = "15") double radiusKm) {
        if (radiusKm <= 0 || radiusKm > 100) {
            throw new IllegalArgumentException("radiusKm must be between 0 and 100");
        }
        return service.baskets(me.getId(), id, lat, lon, radiusKm);
    }

    @PostMapping("/{id}/optimize")
public OptimizeResponse optimize(@AuthenticationPrincipal UserPrincipal me,
                                 @PathVariable Long id,
                                 @Valid @RequestBody OptimizeRequest req) {
    return optimizerService.optimize(me.getId(), id, req);
}
}