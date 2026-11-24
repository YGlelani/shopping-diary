package za.co.shoppingdiary.store.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import za.co.shoppingdiary.auth.UserPrincipal;
import za.co.shoppingdiary.retailer.controller.RetailDirectoryAdminController;
import za.co.shoppingdiary.retailer.controller.RetailDirectoryAdminController.BranchInput;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BranchDirectorySubmissionController {
    private final JdbcClient jdbc;
    private final RetailDirectoryAdminController directory;

    @PostMapping("/stores/submissions")
    public void submit(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody Proposal proposal) {
        int inserted = jdbc.sql("""
            INSERT INTO branch_directory_submissions
                (submitted_by, retailer_id, name, address, city, province, lat, lon, source_url)
            SELECT :user, r.id, :name, :address, :city, :province, :lat, :lon, :source
            FROM retailers r WHERE r.id = :retailer AND r.active
            ON CONFLICT DO NOTHING
            """).param("user", user.getId()).param("retailer", proposal.retailerId())
                .param("name", proposal.name()).param("address", proposal.address())
                .param("city", proposal.city()).param("province", proposal.province())
                .param("lat", proposal.lat()).param("lon", proposal.lon()).param("source", proposal.sourceUrl()).update();
        if (inserted == 0) throw new IllegalArgumentException("Check the retailer or look for an existing submission");
    }

    @GetMapping("/admin/directory/submissions")
    public List<Pending> pending() {
        return jdbc.sql("""
            SELECT q.id, q.retailer_id, r.name AS retailer, q.name, q.address, q.city, q.province,
                   q.lat, q.lon, q.source_url
            FROM branch_directory_submissions q JOIN retailers r ON r.id = q.retailer_id
            WHERE q.status = 'PENDING' ORDER BY q.id LIMIT 100
            """).query(Pending.class).list();
    }

    @PostMapping("/admin/directory/submissions/{id}")
    @Transactional
    public void review(@PathVariable long id, @RequestParam boolean approve) {
        var row = jdbc.sql("""
            SELECT q.id, q.retailer_id, r.name AS retailer, q.name, q.address, q.city, q.province,
                   q.lat, q.lon, q.source_url
            FROM branch_directory_submissions q JOIN retailers r ON r.id = q.retailer_id
            WHERE q.id = :id AND q.status = 'PENDING' FOR UPDATE OF q
            """).param("id", id).query(Pending.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Branch proposal is no longer pending"));

        Long branch = null;
        if (approve) {
            branch = directory.saveBranch(row.retailerId(), "community-" + id, null, new BranchInput(
                    row.name(), row.address(), row.city(), row.province(), row.lat(), row.lon(), true,
                    null, null, row.sourceUrl(), null));
        }

        jdbc.sql("UPDATE branch_directory_submissions SET status = :status, branch_id = :branch WHERE id = :id")
                .param("status", approve ? "APPROVED" : "REJECTED").param("branch", branch).param("id", id).update();
    }

    public record Proposal(@Positive long retailerId, @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 500) String address, @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String province,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lon,
            @NotBlank @Size(max = 500) @Pattern(regexp = "https://\\S+") String sourceUrl) {}
    public record Pending(long id, long retailerId, String retailer, String name, String address, String city,
                          String province, BigDecimal lat, BigDecimal lon, String sourceUrl) {}
}
