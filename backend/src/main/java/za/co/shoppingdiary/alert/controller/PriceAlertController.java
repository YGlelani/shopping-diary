package za.co.shoppingdiary.alert.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.alert.dto.CreateAlertRequest;
import za.co.shoppingdiary.alert.entity.PriceAlert;
import za.co.shoppingdiary.alert.service.PriceAlertService;
import za.co.shoppingdiary.auth.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class PriceAlertController {

    private final PriceAlertService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void createAlert(@AuthenticationPrincipal UserPrincipal me, @Valid @RequestBody CreateAlertRequest req) {
        service.createOrUpdateAlert(me.getId(), req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAlert(@AuthenticationPrincipal UserPrincipal me, @PathVariable Long id) {
        service.deleteAlert(me.getId(), id);
    }

    @GetMapping
    public List<PriceAlert> getMyAlerts(@AuthenticationPrincipal UserPrincipal me) {
        return service.getUserAlerts(me.getId());
    }
}