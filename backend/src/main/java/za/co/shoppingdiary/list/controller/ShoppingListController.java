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
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lists")
@RequiredArgsConstructor
public class ShoppingListController {

    private final ShoppingListService service;
    private final BasketOptimizerService optimizerService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListDetailResponse create(@AuthenticationPrincipal UserPrincipal me,
                                     @Valid @RequestBody CreateListRequest req) {
        return service.create(me.getId(), req);
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
                                           @Valid @RequestBody AddItemRequest req) {
        return service.addItem(me.getId(), id, req);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@AuthenticationPrincipal UserPrincipal me,
                           @PathVariable Long id,
                           @PathVariable Long itemId) {
        service.removeItem(me.getId(), id, itemId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal me,
                       @PathVariable Long id) {
        service.delete(me.getId(), id);
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