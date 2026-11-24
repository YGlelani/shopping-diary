package za.co.shoppingdiary.promotion;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.auth.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SpecialsController {
    private final SpecialsService service;
    private final SpecialsImportService imports;
    public record Created(long id) {}

    @GetMapping("/specials")
    public List<SpecialsService.Deal> browse(@Valid @ModelAttribute SpecialsService.Filters filters) {
        return service.browse(filters,null,null,null,false);
    }

    @GetMapping("/specials/{id}")
    public SpecialsService.Deal detail(@PathVariable long id,@Valid @ModelAttribute SpecialsService.Filters filters) {
        return service.browse(filters,id,null,null,false).stream().findFirst().orElseThrow(SpecialsService::missing);
    }

    @GetMapping("/specials/coverage")
    public List<SpecialsService.Coverage> coverage() { return service.coverage(); }

    @GetMapping("/lists/{id}/specials")
    public List<SpecialsService.Deal> forList(@AuthenticationPrincipal UserPrincipal me,@PathVariable long id,
                                             @Valid @ModelAttribute SpecialsService.Filters filters) {
        return service.browse(filters,null,me.getId(),id,false);
    }

    @GetMapping("/admin/specials")
    public List<SpecialsService.Deal> reviewQueue(@Valid @ModelAttribute SpecialsService.Filters filters) {
        return service.browse(filters,null,null,null,true);
    }

    @GetMapping("/admin/specials/imports")
    public List<SpecialsImportService.Run> imports() { return imports.runs(); }

    @GetMapping("/admin/specials/{id}/audit")
    public List<SpecialsService.Audit> audit(@PathVariable long id) { return service.audit(id); }

    @PostMapping("/admin/specials")
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@AuthenticationPrincipal UserPrincipal actor,@Valid @RequestBody SpecialsService.Edit input) {
        return new Created(service.create(input,actor.getId()));
    }

    @PutMapping("/admin/specials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void edit(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,@RequestParam long revision,
                     @Valid @RequestBody SpecialsService.Edit input) {
        service.update(id,revision,input,actor.getId());
    }

    @PostMapping("/admin/specials/{id}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,
                       @Valid @RequestBody SpecialsService.Review review) {
        service.review(id,review,actor.getId());
    }
}
