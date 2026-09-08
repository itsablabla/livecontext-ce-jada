package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.PlanFeatureRequirement;
import com.apimarketplace.auth.service.PlanFeatureRequirementService;
import com.apimarketplace.auth.service.PlanLimitService;
import com.apimarketplace.common.plan.PlanTier;
import com.apimarketplace.common.web.AdminRoleGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which workflow nodes and catalog integrations each plan may use.
 *
 * <p>The READ is open to any signed-in user because the builder needs it to draw
 * a lock beside a node before the user tries to use it, and it exposes nothing
 * private: it is the price list, in a different shape. The WRITES are platform
 * admin only, on the same {@code X-User-Roles} check the node-type and catalog
 * visibility admin endpoints already use.
 */
@RestController
@RequestMapping("/api/plan-features")
public class PlanFeatureController {

    private static final Logger log = LoggerFactory.getLogger(PlanFeatureController.class);

    private final PlanFeatureRequirementService requirementService;
    private final PlanLimitService planLimitService;

    public PlanFeatureController(PlanFeatureRequirementService requirementService,
                                 PlanLimitService planLimitService) {
        this.requirementService = requirementService;
        this.planLimitService = planLimitService;
    }

    /**
     * GET /api/plan-features - the gate map plus the caller's own plan, so one
     * request answers "what is locked, and am I locked out of it?".
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getForCaller(
            @RequestHeader(value = "X-User-ID", required = false) String providerId) {
        String planCode = planLimitService.getPlanCode(providerId);
        Map<String, Object> body = new HashMap<>();
        body.put("requirements", requirementService.requirements());
        body.put("planCode", planCode);
        body.put("selectablePlans", PlanTier.selectableCodes());
        return ResponseEntity.ok(body);
    }

    /**
     * GET /api/plan-features/admin - every stored requirement, with its label and
     * who last changed it.
     */
    @GetMapping("/admin")
    public ResponseEntity<?> listForAdmin(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        List<Map<String, Object>> rows = requirementService.list().stream()
                .map(PlanFeatureController::toMap)
                .toList();
        Map<String, Object> body = new HashMap<>();
        body.put("requirements", rows);
        body.put("selectablePlans", PlanTier.selectableCodes());
        return ResponseEntity.ok(body);
    }

    /**
     * PUT /api/plan-features/admin - set or clear one requirement.
     * Body: {@code {"featureKey":"api:youtube-data-api","minPlan":"PRO","label":"YouTube"}}.
     * A {@code minPlan} of FREE (or blank) clears the requirement.
     */
    @PutMapping("/admin")
    public ResponseEntity<?> upsert(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) String providerId,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        String featureKey = asString(body.get("featureKey"));
        String minPlan = asString(body.get("minPlan"));
        String label = asString(body.get("label"));
        if (featureKey == null || featureKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'featureKey'"));
        }
        try {
            PlanFeatureRequirement saved = requirementService.set(featureKey, minPlan, label, providerId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("featureKey", featureKey.trim().toLowerCase());
            // null = the requirement was cleared, so the feature is back on every plan.
            response.put("minPlan", saved != null ? saved.getMinPlan() : null);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[PlanFeatures] Failed to set requirement for '{}'", featureKey, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to save requirement"));
        }
    }

    private static Map<String, Object> toMap(PlanFeatureRequirement row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("featureKey", row.getFeatureKey());
        map.put("minPlan", row.getMinPlan());
        map.put("label", row.getLabel());
        map.put("updatedAt", row.getUpdatedAt() != null ? row.getUpdatedAt().toString() : null);
        map.put("updatedBy", row.getUpdatedBy());
        return map;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
