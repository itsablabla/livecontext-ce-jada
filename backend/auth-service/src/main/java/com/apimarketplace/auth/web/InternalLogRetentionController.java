package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.LogRetentionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Service-to-service read of the execution-log retention windows, per WORKSPACE.
 *
 * <p>Consumed by the purge jobs in orchestrator-service and agent-service, which
 * sweep their own schema and cannot join {@code auth} (cross-schema SQL is
 * forbidden). A nightly sweep touches many workspaces, so the shape is a batch:
 * a job must not make one call per workspace.
 *
 * <p><b>The key is the organization id, and the window is the organization
 * OWNER's plan.</b> Not the tenant who produced the rows: a plan applies to the
 * workspaces its holder owns, and a member's own workspaces are not widened by
 * the workspaces they merely work in. See {@link LogRetentionService}.
 *
 * <p><b>A workspace absent from the response retains indefinitely.</b> That is
 * the contract, and it is what makes every failure mode safe: an unknown id, an
 * unidentifiable owner, an unknown plan code, a self-hosted install and an
 * enterprise agreement all simply do not appear. A caller must never invert this
 * and read absence as "no retention".
 *
 * <p>POST rather than GET because the id list is unbounded and would not fit a
 * query string. It reads state and changes none.
 *
 * <p>Lives under {@code /api/internal}, which the gateway does not route from
 * the edge.
 */
@RestController
@RequestMapping("/api/internal/auth/log-retention")
public class InternalLogRetentionController {

    /** Bounds one request so a runaway caller cannot ask about the whole table. */
    static final int MAX_IDS_PER_REQUEST = 500;

    private final LogRetentionService logRetentionService;

    public InternalLogRetentionController(LogRetentionService logRetentionService) {
        this.logRetentionService = logRetentionService;
    }

    /**
     * @param organizationIds the workspaces to look up, as a bare JSON array
     *                        (matching {@code /api/internal/auth/users/resolve-batch})
     * @return organization id to days, carrying ONLY the workspaces with a finite
     *         window
     */
    @PostMapping
    public ResponseEntity<Map<String, Integer>> retentionDays(@RequestBody List<String> organizationIds) {
        List<String> requested = organizationIds == null ? List.of() : organizationIds;
        if (requested.size() > MAX_IDS_PER_REQUEST) {
            // Refuse rather than truncate: a truncated answer is indistinguishable
            // from "these workspaces retain", so the caller would silently skip
            // work it believes it did. Failing the call makes the caller page properly.
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(logRetentionService.retentionDaysFor(requested));
    }
}
