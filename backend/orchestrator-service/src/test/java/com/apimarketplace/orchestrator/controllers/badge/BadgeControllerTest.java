package com.apimarketplace.orchestrator.controllers.badge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.BadgeProfileDto;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.badge.BadgeFamily;
import com.apimarketplace.orchestrator.services.badge.BadgeMetric;
import com.apimarketplace.orchestrator.services.badge.BadgeService;
import com.apimarketplace.orchestrator.services.badge.BadgeTier;
import com.apimarketplace.orchestrator.services.badge.BadgeView;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeController")
class BadgeControllerTest {

    private static final String TENANT = "42";

    private static final BadgeView UNLOCKED = new BadgeView(
            "builder_1", BadgeFamily.BUILDER, BadgeTier.BRONZE,
            BadgeMetric.WORKFLOWS_CREATED, 1, 3, true, Instant.parse("2026-05-01T00:00:00Z"));

    @Mock private BadgeService badgeService;
    @Mock private TenantResolver tenantResolver;
    @Mock private AuthClient authClient;
    @Mock private HttpServletRequest request;

    private BadgeController controller;

    @BeforeEach
    void setUp() {
        controller = new BadgeController(badgeService, tenantResolver, authClient);
    }

    @Test
    @DisplayName("the caller's own grid is scoped to the workspace header they sent")
    void ownGridPassesTheActiveWorkspace() {
        when(tenantResolver.resolve(request)).thenReturn(TENANT);
        when(request.getHeader("X-Organization-ID")).thenReturn("org-7");
        when(badgeService.getBadgesForUser(TENANT, "org-7")).thenReturn(List.of(UNLOCKED));

        ResponseEntity<List<BadgeView>> response = controller.getMyBadges(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(UNLOCKED);
        verify(tenantResolver).validate(TENANT);
    }

    @Test
    @DisplayName("a visible profile's unlocked badges are readable without a session")
    void publicBadgesAreServedForAVisibleProfile() {
        when(authClient.getBadgeProfile(TENANT))
                .thenReturn(new BadgeProfileDto(TENANT, "2026-05-01T00:00:00", true));
        when(badgeService.getPublicBadgesForUser(TENANT)).thenReturn(List.of(UNLOCKED));

        ResponseEntity<List<BadgeView>> response = controller.getPublicBadges(TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(UNLOCKED);
    }

    @Test
    @DisplayName("a PRIVATE profile answers 404 and never reaches the badge store")
    void privateProfileIsNotFound() {
        when(authClient.getBadgeProfile(TENANT))
                .thenReturn(new BadgeProfileDto(TENANT, "2026-05-01T00:00:00", false));

        ResponseEntity<List<BadgeView>> response = controller.getPublicBadges(TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(badgeService);
    }

    @Test
    @DisplayName("an unknown user answers 404 too, so the endpoint is not an existence oracle")
    void unknownUserIsIndistinguishableFromPrivate() {
        when(authClient.getBadgeProfile("999999")).thenReturn(null);

        ResponseEntity<List<BadgeView>> response = controller.getPublicBadges("999999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(badgeService);
    }

    @Test
    @DisplayName("a blank user id is rejected before any cross-service call")
    void blankUserIdIsRejectedEarly() {
        ResponseEntity<List<BadgeView>> response = controller.getPublicBadges("  ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(authClient);
        verifyNoInteractions(badgeService);
    }
}
