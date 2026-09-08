package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.CeLinkEntitlementsService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.CreditConsumptionDeadLetterService;
import com.apimarketplace.auth.service.ModelPricingService;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.OrgRestrictionQueryService;
import com.apimarketplace.auth.service.PlanLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code GET /users/{userId}/badge-profile}.
 *
 * <p>Two things must hold or the trophy feature misbehaves in ways nobody sees:
 * the join date has to survive the hop as UTC (it decides who counts as a
 * founder), and {@code pageVisible} has to be false ONLY for PRIVATE (it is the
 * gate the anonymous public badge endpoint leans on).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController.getBadgeProfile")
class InternalAuthControllerBadgeProfileTest {

    @Mock private OrgRestrictionQueryService restrictionService;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private ModelPricingService modelPricingService;
    @Mock private PlanLimitService planLimitService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private ObjectProvider<CeLinkService> ceLinkServiceProvider;
    @Mock private ObjectProvider<CeLinkEntitlementsService> ceLinkEntitlementsServiceProvider;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private UserRepository userRepository;

    private InternalAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAuthController(
                restrictionService, deadLetterService,
                onboardingRepository,
                org.mockito.Mockito.mock(OnboardingService.class),
                modelPricingService, planLimitService,
                memberRepository, ceLinkServiceProvider,
                ceLinkEntitlementsServiceProvider,
                userProfileRepository,
                userRepository);
    }

    private User enabledUser(long id) {
        User user = new User();
        user.setId(id);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.of(2026, 3, 4, 10, 15, 30));
        return user;
    }

    @Test
    @DisplayName("a numeric id returns the join date and a visible page")
    void numericIdReturnsJoinDateAndVisibility() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(enabledUser(42L)));
        when(userProfileRepository.findByUserId(42L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getBadgeProfile("42");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("userId")).isEqualTo("42");
        // Zone-less ISO: the consumer reads it as UTC, which is how it is stored.
        assertThat(body.get("joinedAt")).isEqualTo("2026-03-04T10:15:30");
        assertThat(body.get("pageVisible")).isEqualTo(true);
    }

    @Test
    @DisplayName("no profile row means the default UNLISTED, which is visible")
    void missingProfileRowIsVisible() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(enabledUser(42L)));
        when(userProfileRepository.findByUserId(42L)).thenReturn(Optional.empty());

        assertThat(controller.getBadgeProfile("42").getBody().get("pageVisible")).isEqualTo(true);
    }

    @Test
    @DisplayName("an UNLISTED profile is visible - only PRIVATE hides the page")
    void unlistedProfileIsVisible() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(enabledUser(42L)));
        UserProfileEntity profile = new UserProfileEntity(42L);
        profile.setProfileVisibility(UserProfileEntity.VISIBILITY_UNLISTED);
        when(userProfileRepository.findByUserId(42L)).thenReturn(Optional.of(profile));

        assertThat(controller.getBadgeProfile("42").getBody().get("pageVisible")).isEqualTo(true);
    }

    @Test
    @DisplayName("a PRIVATE profile reports pageVisible=false so the public badge page can 404")
    void privateProfileIsNotVisible() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(enabledUser(42L)));
        UserProfileEntity profile = new UserProfileEntity(42L);
        profile.setProfileVisibility(UserProfileEntity.VISIBILITY_PRIVATE);
        when(userProfileRepository.findByUserId(42L)).thenReturn(Optional.of(profile));

        assertThat(controller.getBadgeProfile("42").getBody().get("pageVisible")).isEqualTo(false);
    }

    @Test
    @DisplayName("a provider id (non-numeric) resolves through the provider lookup")
    void providerIdResolvesThroughProviderLookup() {
        when(userRepository.findByProviderId("kc-sub-abc")).thenReturn(Optional.of(enabledUser(42L)));
        when(userProfileRepository.findByUserId(42L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getBadgeProfile("kc-sub-abc");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // The body echoes the RESOLVED numeric id, not the provider id it was
        // asked with - the caller keys its badge rows on the numeric tenant.
        assertThat(response.getBody().get("userId")).isEqualTo("42");
    }

    @Test
    @DisplayName("an unknown user is 404")
    void unknownUserIsNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThat(controller.getBadgeProfile("999").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a disabled account is 404, indistinguishable from a missing one")
    void disabledUserIsNotFound() {
        User user = enabledUser(42L);
        user.setEnabled(false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThat(controller.getBadgeProfile("42").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a blank id is rejected before any repository lookup")
    void blankIdIsBadRequest() {
        assertThat(controller.getBadgeProfile("  ").getStatusCode().value()).isEqualTo(400);
        org.mockito.Mockito.verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a user with no creation timestamp simply omits joinedAt")
    void missingCreatedAtOmitsTheKey() {
        User user = enabledUser(42L);
        user.setCreatedAt(null);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userProfileRepository.findByUserId(42L)).thenReturn(Optional.empty());

        assertThat(controller.getBadgeProfile("42").getBody()).doesNotContainKey("joinedAt");
    }
}
