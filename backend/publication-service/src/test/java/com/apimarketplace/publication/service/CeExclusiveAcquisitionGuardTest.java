package com.apimarketplace.publication.service;

import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The install gate. Two features reach it and they no longer mean the same thing: a local CLI
 * agent has no host on managed cloud at any price, while vector search runs there from a plan. So
 * the first is refused outright and the second only for a workspace below the bar, with the plan
 * named. Every self-hosted edition installs both normally.
 */
@DisplayName("CeExclusiveAcquisitionGuard")
class CeExclusiveAcquisitionGuardTest {

    private static AppEditionProvider edition(String value) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", value);
        return new AppEditionProvider(env);
    }

    private static WorkflowPublicationEntity publication(boolean ceExclusive) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(UUID.randomUUID());
        publication.setCeExclusive(ceExclusive);
        publication.setCeExclusiveFeatures(ceExclusive ? List.of("CLI_AGENT") : List.of());
        return publication;
    }

    /** A publication whose only special capability is embeddings. */
    private static WorkflowPublicationEntity vectorPublication() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(UUID.randomUUID());
        // Recorded, but not blocking: that pairing IS the change.
        publication.setCeExclusive(false);
        publication.setCeExclusiveFeatures(List.of("VECTOR_SEARCH"));
        return publication;
    }

    private static com.apimarketplace.auth.client.entitlement.PlanFeatureGate plans(String required) {
        var gate = org.mockito.Mockito.mock(com.apimarketplace.auth.client.entitlement.PlanFeatureGate.class);
        org.mockito.Mockito.lenient()
                .when(gate.upgradeRequiredFor(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(required);
        return gate;
    }

    @Test
    @DisplayName("a vector app installs on cloud when the workspace's plan includes it")
    void vectorInstallsWhenThePlanCovers() {
        CeExclusiveAcquisitionGuard guard =
                new CeExclusiveAcquisitionGuard(edition("cloud"), plans(null));

        assertThatCode(() -> guard.check(vectorPublication(), "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a vector app is refused for a workspace below the bar, NAMING the plan")
    void vectorRefusedBelowTheBar() {
        CeExclusiveAcquisitionGuard guard =
                new CeExclusiveAcquisitionGuard(edition("cloud"), plans("PRO"));

        assertThatThrownBy(() -> guard.check(vectorPublication(), "42"))
                // Not the CE exception: that one is documented to users and agents as terminal,
                // and this refusal is lifted by an upgrade.
                .isInstanceOf(PublicationPlanUpgradeRequiredException.class)
                .hasMessageContaining("PRO");
    }

    @Test
    @DisplayName("a vector app is never plan-gated on a self-hosted deployment")
    void vectorNeverGatedSelfHosted() {
        CeExclusiveAcquisitionGuard guard =
                new CeExclusiveAcquisitionGuard(edition("ce"), plans("PRO"));

        assertThatCode(() -> guard.check(vectorPublication(), "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with no plan gate wired on shared cloud, a vector app is REFUSED rather than half-installed")
    void vectorRefusedWithoutAPlanGate() {
        // The datasource gate refuses in exactly this configuration, so allowing the install here
        // would deliver an app whose vector columns the clone then strips in silence: the outcome
        // this refusal exists to prevent. The two components have to reason the same way about
        // the same condition.
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("cloud"), null);

        assertThatThrownBy(() -> guard.check(vectorPublication(), "42"))
                .isInstanceOf(PublicationPlanUpgradeRequiredException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(PublicationPlanUpgradeRequiredException.class))
                // No plan is NAMED, because none could be read. Guessing one would send the
                // customer to buy a tier an admin may not have configured.
                .extracting(PublicationPlanUpgradeRequiredException::getRequiredPlan)
                .isNull();
    }

    @Test
    @DisplayName("dedicated cloud never plan-gates vectors: single-tenant database, nothing to price")
    void dedicatedCloudDoesNotPriceVectors() {
        // It still BLOCKS a CLI-agent app (no bridge host there either), which the case below
        // pins; only the vector half is free.
        CeExclusiveAcquisitionGuard guard =
                new CeExclusiveAcquisitionGuard(edition("dedicated-cloud"), plans("PRO"));

        assertThatCode(() -> guard.check(vectorPublication(), "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a row flagged exclusive but naming nothing is still refused: drifted data reads the old way")
    void flaggedButUnexplainedStillBlocks() {
        WorkflowPublicationEntity drifted = new WorkflowPublicationEntity();
        drifted.setId(UUID.randomUUID());
        drifted.setCeExclusive(true);
        drifted.setCeExclusiveFeatures(List.of());
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("cloud"), null);

        assertThatThrownBy(() -> guard.check(drifted, "42"))
                .isInstanceOf(CeExclusivePublicationException.class);
    }

    @Test
    @DisplayName("the vector feature key matches the one datasource-service gates on")
    void vectorKeyMatchesTheGate() {
        // Two services, two spellings, no shared module: a rename on one side would silently
        // un-gate the other, so the string is pinned here as well as in the frontend hook.
        assertThat(CeExclusiveAcquisitionGuard.VECTOR_SEARCH_FEATURE_KEY)
                .isEqualTo("feature:vector_search");
    }

    @Test
    @DisplayName("managed cloud refuses a CE-exclusive publication and names the features")
    void cloudRefusesCeExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("cloud"), null);

        assertThatThrownBy(() -> guard.check(publication(true), "42"))
                .isInstanceOf(CeExclusivePublicationException.class)
                .hasMessage(CeExclusiveAcquisitionGuard.BLOCKED_MESSAGE)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(CeExclusivePublicationException.class))
                .extracting(CeExclusivePublicationException::getFeatures)
                .isEqualTo(List.of("CLI_AGENT"));
    }

    @Test
    @DisplayName("dedicated cloud is managed cloud too, so it refuses as well")
    void dedicatedCloudRefusesCeExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("dedicated-cloud"), null);

        assertThatThrownBy(() -> guard.check(publication(true), "42"))
                .isInstanceOf(CeExclusivePublicationException.class);
    }

    @Test
    @DisplayName("Community Edition installs a CE-exclusive publication - that is the point")
    void ceInstallsCeExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("ce"), null);

        assertThatCode(() -> guard.check(publication(true), "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("self-hosted enterprise installs it too (self-hosted, not managed cloud)")
    void selfHostedEnterpriseInstallsCeExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("self-hosted-enterprise"), null);

        assertThatCode(() -> guard.check(publication(true), "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a normal publication is never blocked, including on managed cloud")
    void cloudAllowsNonExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("cloud"), null);

        assertThatCode(() -> guard.check(publication(false), "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null publication is a no-op rather than an NPE")
    void nullPublicationIsNoOp() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("cloud"), null);

        assertThatCode(() -> guard.check(null, "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the refusal message names the constraint and the resolution, with no internals")
    void messageIsUserFacing() {
        assertThat(CeExclusiveAcquisitionGuard.BLOCKED_MESSAGE)
                .contains("self-hosted")
                .contains("CLI agent")
                // It must no longer blame vector search: that is installable here, for a price.
                .doesNotContain("vector")
                .doesNotContain("/api/")
                .doesNotContain("null");
    }
}
