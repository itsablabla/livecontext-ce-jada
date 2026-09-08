package com.apimarketplace.orchestrator.controllers.badge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.BadgeProfileDto;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.badge.BadgeService;
import com.apimarketplace.orchestrator.services.badge.BadgeView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Trophy endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/badges/me} - the signed-in user's full grid: unlocked
 *       badges plus locked ones with live progress. Evaluates on the way in, so
 *       the response already contains anything just earned.</li>
 *   <li>{@code GET /api/badges/public/{userId}} - the unlocked badges shown on
 *       someone's public profile page. Anonymous by design (the profile page is
 *       server-rendered for logged-out visitors) and therefore gated on the
 *       owner's profile visibility.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/badges")
public class BadgeController {

    private final BadgeService badgeService;
    private final TenantResolver tenantResolver;
    private final AuthClient authClient;

    public BadgeController(BadgeService badgeService, TenantResolver tenantResolver, AuthClient authClient) {
        this.badgeService = badgeService;
        this.tenantResolver = tenantResolver;
        this.authClient = authClient;
    }

    @GetMapping("/me")
    public ResponseEntity<List<BadgeView>> getMyBadges(HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.validate(tenantId);
        return ResponseEntity.ok(
                badgeService.getBadgesForUser(tenantId, request.getHeader("X-Organization-ID")));
    }

    /**
     * Unlocked badges for a public profile.
     *
     * <p><b>Why the visibility check.</b> This path is on the gateway's public
     * allow-list so the server-rendered {@code /u/{handle}} page can read it
     * without a session, and the argument is a sequential numeric user id. Left
     * ungated, walking 1..N would turn it into a census of which accounts exist
     * and how active they are. Asking auth-service the same question the profile
     * page itself asks ({@code pageVisible}, false only for PRIVATE) keeps the
     * two surfaces in step: hide the profile, and the badges go with it.
     *
     * <p>404 for a private, unknown or disabled user - indistinguishable, so
     * this cannot become a user-existence oracle either.
     */
    @GetMapping("/public/{userId}")
    public ResponseEntity<List<BadgeView>> getPublicBadges(@PathVariable String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        BadgeProfileDto profile = authClient.getBadgeProfile(userId);
        if (profile == null || !profile.pageVisible()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(badgeService.getPublicBadgesForUser(userId));
    }
}
