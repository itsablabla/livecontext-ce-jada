package com.apimarketplace.datasource.services;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorFeatureGate}: who may use vector columns, and the snapshot-clone sanitizer.
 *
 * <p>The answer used to be a property of the DEPLOYMENT, and this class asserted it that way:
 * self-hosted allowed, managed cloud refused, decided once at construction. Since 2026-09-03 it is
 * a property of the OWNING WORKSPACE'S PLAN on cloud, so what has to be pinned changed shape
 * entirely. Three things matter now and each has its own case below: an edition that owns its
 * database is never gated (self-hosted AND dedicated cloud), shared cloud asks about the table's
 * OWNER rather than the caller, and not being able to READ the answer resolves to allowed while
 * having no way to ASK it resolves to refused.
 */
@DisplayName("VectorFeatureGate")
class VectorFeatureGateTest {

    private static final String OWNER = "42";

    private static com.apimarketplace.common.web.AppEditionProvider edition(String appEdition) {
        MockEnvironment env = new MockEnvironment();
        if (appEdition != null) {
            env.setProperty("app.edition", appEdition);
        }
        return new com.apimarketplace.common.web.AppEditionProvider(env);
    }

    /** A cloud gate whose plan gate answers {@code required} for the vector key. */
    private static VectorFeatureGate cloudGate(String required) {
        PlanFeatureGate plans = mock(PlanFeatureGate.class);
        when(plans.upgradeRequiredFor(anyString(), anyList())).thenReturn(required);
        when(plans.allows(anyString(), anyList())).thenReturn(required == null);
        return new VectorFeatureGate(edition("cloud"), plans);
    }

    private static ColumnMappingSpec spec(ColumnType type) {
        return new ColumnMappingSpec("data.col", type, ColumnStructure.SCALAR, Map.of(), Map.of());
    }

    /** The editions that own their database, and so are never priced for using it. */
    @Nested
    @DisplayName("single-tenant editions")
    class OwnsItsDatabase {

        @Test
        @DisplayName("is never gated, whatever the plan gate would say")
        void neverGated() {
            PlanFeatureGate plans = mock(PlanFeatureGate.class);
            when(plans.allows(anyString(), anyList())).thenReturn(false);

            assertThat(new VectorFeatureGate(edition("ce"), plans).isVectorAllowed(OWNER)).isTrue();
            assertThat(new VectorFeatureGate(edition("self-hosted-enterprise"), plans).isVectorAllowed(OWNER))
                    .isTrue();
            // Not even asked: a self-hosted install owns its database, so pricing it would be wrong
            // even if a stray requirement row existed.
            verify(plans, never()).allows(anyString(), anyList());
        }

        @Test
        @DisplayName("dedicated cloud is never priced either: single-tenant database, nothing to share")
        void dedicatedCloudIsNeverPriced() {
            // Not an accident of which editions PlanFeatureGate happens to be enabled on: that
            // bean is enabled on isCloud() (shared CLOUD only), so relying on it would have made
            // this true by luck. The gate decides it, and this pins the decision.
            PlanFeatureGate plans = mock(PlanFeatureGate.class);
            when(plans.allows(anyString(), anyList())).thenReturn(false);

            assertThat(new VectorFeatureGate(edition("dedicated-cloud"), plans).isVectorAllowed(OWNER))
                    .isTrue();
            verify(plans, never()).allows(anyString(), anyList());
        }

        @Test
        @DisplayName("an invalid app.edition fails at construction rather than resolving to a free pass")
        void invalidEditionFailsClosed() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("app.edition", "not-an-edition");

            // AppEditionProvider throws, so the context does not start. A gate that swallowed this
            // and defaulted would be deciding a pricing question from a typo.
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> new VectorFeatureGate(
                            new com.apimarketplace.common.web.AppEditionProvider(env), null));
        }

        @Test
        @DisplayName("the ce Spring profile alone is enough, with no app.edition set")
        void ceProfileFallbackAllows() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce", "docker");

            assertThat(new VectorFeatureGate(
                    new com.apimarketplace.common.web.AppEditionProvider(env), null)
                    .isVectorAllowed(OWNER)).isTrue();
        }
    }

    @Nested
    @DisplayName("managed cloud")
    class ManagedCloud {

        @Test
        @DisplayName("allows a workspace whose plan meets the bar")
        void allowsAtOrAboveTheBar() {
            assertThat(cloudGate(null).isVectorAllowed(OWNER)).isTrue();
        }

        @Test
        @DisplayName("refuses a workspace below the bar")
        void refusesBelowTheBar() {
            assertThat(cloudGate("PRO").isVectorAllowed(OWNER)).isFalse();
        }

        @Test
        @DisplayName("asks about the single vector key, so an admin has one switch to move")
        void asksAboutTheVectorKey() {
            PlanFeatureGate plans = mock(PlanFeatureGate.class);
            when(plans.allows(anyString(), anyList())).thenReturn(true);

            new VectorFeatureGate(edition("cloud"), plans).isVectorAllowed(OWNER);

            verify(plans).allows(eq(OWNER), eq(List.of("feature:vector_search")));
        }

        @Test
        @DisplayName("the no-config default is treated as shared cloud, not as a free pass")
        void defaultEditionIsGated() {
            // AppEditionProvider resolves an absent app.edition to CLOUD. A gate that read that as
            // "unknown, allow" would hand vectors to every workspace of a misconfigured install.
            assertThat(new VectorFeatureGate(edition(null), null).isVectorAllowed(OWNER)).isFalse();
        }

        @Test
        @DisplayName("refuses when no plan gate is wired at all, rather than opening the shared database")
        void noPlanGateRefuses() {
            // Not a lookup failure: an assembly with no way to ask the question. Failing open here
            // would hand embeddings to every free workspace on the shared Postgres.
            assertThat(new VectorFeatureGate(edition("cloud"), null).isVectorAllowed(OWNER)).isFalse();
        }

        @Test
        @DisplayName("a lookup that throws is allowed, so auth trouble never stops a paid RAG workflow")
        void lookupFailureFailsOpen() {
            PlanFeatureGate plans = mock(PlanFeatureGate.class);
            when(plans.allows(anyString(), anyList())).thenThrow(new RuntimeException("auth down"));

            assertThat(new VectorFeatureGate(edition("cloud"), plans).isVectorAllowed(OWNER)).isTrue();
        }
    }

    @Nested
    @DisplayName("the refusal message")
    class Messages {

        @Test
        @DisplayName("names the plan that lifts it, because a wall with no door is not actionable")
        void namesThePlan() {
            assertThat(cloudGate("PRO").deniedMessage(OWNER))
                    .contains("PRO")
                    .contains("Upgrade");
        }

        @Test
        @DisplayName("falls back to the plain sentence when no plan can be named")
        void fallsBackWhenNoPlanKnown() {
            assertThat(new VectorFeatureGate(edition("cloud"), null).deniedMessage(OWNER))
                    .isEqualTo(VectorFeatureGate.DISABLED_MESSAGE);
        }

        @Test
        @DisplayName("says nothing about editions: a cloud workspace CAN have this, for a price")
        void doesNotClaimSelfHostedOnly() {
            assertThat(VectorFeatureGate.DISABLED_MESSAGE)
                    .doesNotContain("self-hosted")
                    .doesNotContain("Community Edition");
        }
    }

    @Nested
    @DisplayName("the snapshot-clone sanitizer")
    class Sanitizer {

        @Test
        @DisplayName("removes ONLY vector columns for a workspace that may not keep them, preserving order")
        void stripRemovesOnlyVectorColumns() {
            Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
            mapping.put("title", spec(ColumnType.TEXT));
            mapping.put("embedding", spec(ColumnType.VECTOR));
            mapping.put("score", spec(ColumnType.NUMBER));

            Map<String, ColumnMappingSpec> stripped =
                    cloudGate("PRO").stripDisallowedVectorColumns(OWNER, mapping);

            assertThat(stripped.keySet()).containsExactly("title", "score");
        }

        @Test
        @DisplayName("is identity when the workspace may keep them, and for null or vector-free specs")
        void stripIsIdentityWhenAllowedOrIrrelevant() {
            Map<String, ColumnMappingSpec> withVector = new LinkedHashMap<>();
            withVector.put("embedding", spec(ColumnType.VECTOR));

            assertThat(cloudGate(null).stripDisallowedVectorColumns(OWNER, withVector)).isSameAs(withVector);
            assertThat(cloudGate("PRO").stripDisallowedVectorColumns(OWNER, null)).isNull();

            Map<String, ColumnMappingSpec> noVector = new LinkedHashMap<>();
            noVector.put("title", spec(ColumnType.TEXT));
            assertThat(cloudGate("PRO").stripDisallowedVectorColumns(OWNER, noVector)).isSameAs(noVector);
        }

        @Test
        @DisplayName("names the columns it would strip, so the caller can purge the column order too")
        void disallowedColumnsAreNamed() {
            Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
            mapping.put("title", spec(ColumnType.TEXT));
            mapping.put("embedding", spec(ColumnType.VECTOR));

            assertThat(cloudGate("PRO").disallowedVectorColumns(OWNER, mapping)).containsExactly("embedding");
            assertThat(cloudGate(null).disallowedVectorColumns(OWNER, mapping)).isEmpty();
        }
    }

    @Test
    @DisplayName("findVectorColumn is pure schema inspection: no plan, no edition")
    void findVectorColumn() {
        Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
        mapping.put("title", spec(ColumnType.TEXT));
        mapping.put("embedding", spec(ColumnType.VECTOR));

        // It drives HNSW index create/drop, which must follow the data whether or not the
        // workspace may still query it.
        assertThat(VectorFeatureGate.findVectorColumn(mapping)).isEqualTo("embedding");
        assertThat(VectorFeatureGate.findVectorColumn(Map.of("t", spec(ColumnType.TEXT)))).isNull();
        assertThat(VectorFeatureGate.findVectorColumn(null)).isNull();
    }
}
