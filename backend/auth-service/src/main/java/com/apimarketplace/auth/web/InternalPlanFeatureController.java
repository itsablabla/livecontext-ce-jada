package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.PlanFeatureRequirementService;
import com.apimarketplace.auth.service.PlanLimitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Service-to-service read of the plan gate. Consumed by {@code auth-client}'s
 * {@code PlanFeatureGate}, which caches it, so the shape is deliberately one
 * flat map: a caller that asks about twenty nodes must not make twenty calls.
 *
 * <p>Lives under {@code /api/internal}, which the gateway does not route from
 * the edge. The user-facing equivalent is {@code /api/plan-features}.
 */
@RestController
@RequestMapping("/api/internal/auth/plan-features")
public class InternalPlanFeatureController {

    private final PlanFeatureRequirementService requirementService;
    private final PlanLimitService planLimitService;

    public InternalPlanFeatureController(PlanFeatureRequirementService requirementService,
                                         PlanLimitService planLimitService) {
        this.requirementService = requirementService;
        this.planLimitService = planLimitService;
    }

    /**
     * The whole gate map, and - when the caller passes an {@code X-User-ID} - the
     * plan that user is on, so a gate can answer in one round-trip.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> requirements(
            @RequestHeader(value = "X-User-ID", required = false) String providerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("requirements", requirementService.requirements());
        if (providerId != null && !providerId.isBlank()) {
            body.put("planCode", planLimitService.getPlanCode(providerId));
        }
        return ResponseEntity.ok(body);
    }
}
