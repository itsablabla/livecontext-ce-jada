package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PlanLimitService.PlanCodeResolution;
import com.apimarketplace.auth.service.license.EnterpriseLicenseService;
import com.apimarketplace.auth.service.license.EnterpriseLicenseStatus;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PlanLimitService#resolvePlanCode} exists so a DESTRUCTIVE caller can tell
 * "this account exists and pays nothing" from "no account matched this id", which
 * {@code getPlanCode} collapses onto one sentinel. These assertions pin the
 * distinction, because everything downstream of it deletes data.
 */
@DisplayName("PlanLimitService.resolvePlanCode")
class PlanLimitServiceResolvePlanCodeTest {

    private UserRepository userRepository;
    private SubscriptionRepository subscriptionRepository;
    private AppEditionProvider editionProvider;
    private EnterpriseLicenseService licenseService;
    private PlanLimitService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        editionProvider = mock(AppEditionProvider.class);
        licenseService = mock(EnterpriseLicenseService.class);
        service = new PlanLimitService(userRepository, subscriptionRepository,
                editionProvider, licenseService);
    }

    @Nested
    @DisplayName("Cloud")
    class Cloud {

        @BeforeEach
        void cloudEdition() {
            when(editionProvider.isSelfHostedEnterprise()).thenReturn(false);
        }

        @Test
        @DisplayName("An id no account matches is reported UNRESOLVED, not as a free account")
        void unknownIdIsUnresolved() {
            when(userRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(Optional.empty());
            when(userRepository.findByProviderId(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(Optional.empty());

            PlanCodeResolution resolution = service.resolvePlanCode("404-nobody");

            assertFalse(resolution.accountResolved(),
                    "collapsing this onto the sentinel is what would delete a paying customer's journal");
            assertEquals(PlanLimitService.NO_SUBSCRIPTION, resolution.planCode());
        }

        @Test
        @DisplayName("A blank id resolves nothing rather than falling through to a lookup")
        void blankIdIsUnresolved() {
            assertFalse(service.resolvePlanCode("   ").accountResolved());
            assertFalse(service.resolvePlanCode(null).accountResolved());
        }
    }

    @Nested
    @DisplayName("Self-hosted enterprise")
    class SelfHostedEnterprise {

        @BeforeEach
        void selfHostedEdition() {
            when(editionProvider.isSelfHostedEnterprise()).thenReturn(true);
        }

        @Test
        @DisplayName("An active licence answers with its plan, for any id")
        void activeLicenceResolves() {
            when(licenseService.currentStatus())
                    .thenReturn(activeLicence("ENTERPRISE_PREMIUM"));

            PlanCodeResolution resolution = service.resolvePlanCode("anything");

            assertTrue(resolution.accountResolved());
            assertEquals("ENTERPRISE_PREMIUM", resolution.planCode());
        }

        /**
         * The one that matters. This branch answers for ANY id without touching the
         * user table, so reporting a lapsed licence as "resolved, no subscription"
         * would let a destructive caller read it as the free tier and act on the
         * whole install at once (the first such caller, execution-log retention,
         * has since moved to a per-workspace reader that never runs self-hosted,
         * but the contract stays for the next one). A licence lapse must degrade
         * features, never authorise a deletion.
         */
        @Test
        @DisplayName("A lapsed licence is UNRESOLVED, so it can never be read as the free tier")
        void lapsedLicenceIsUnresolved() {
            when(licenseService.currentStatus())
                    .thenReturn(EnterpriseLicenseStatus.inactive("expired"));

            PlanCodeResolution resolution = service.resolvePlanCode("anything");

            assertFalse(resolution.accountResolved(),
                    "an expired licence must never read as a positively identified free account");
        }
    }

    /** A real record, not a mock: {@code EnterpriseLicenseStatus} is final. */
    private static EnterpriseLicenseStatus activeLicence(String planCode) {
        return new EnterpriseLicenseStatus(true, "ok", "lic-1", "Acme", planCode,
                java.time.Instant.now().plusSeconds(86_400),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
    }
}
