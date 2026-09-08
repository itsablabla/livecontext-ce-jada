package com.apimarketplace.datasource.services;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.events.DatasourceRowEventPublisher;
import com.apimarketplace.datasource.persistence.DataSourceEnhancedRepositories;
import com.apimarketplace.datasource.crud.repository.VectorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the bug found in the first audit of the plan gate: the REST "add column"
 * path asked whether the CALLER's plan includes vector columns, and only afterwards resolved the
 * table's OWNER for the write.
 *
 * <p>On an org-shared table that is wrong in both directions and both are user-visible. A member on
 * a cheaper personal plan could not add an embedding column to a table their PAID workspace owns,
 * and was told to "upgrade the workspace" that is already upgraded. In the other direction a member
 * on a paid plan could add one to a table owned by a workspace without the capability, producing a
 * column that every later write and every search, all owner-gated, would then refuse.
 *
 * <p>The two tests below are the two directions. They would both have passed before the fix only by
 * accident of the caller and the owner being the same tenant, which is why the fixture makes them
 * different on purpose.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("vector columns are gated on the table's OWNER, not the caller")
class VectorColumnOwnerGatingTest {

    private static final Long DATA_SOURCE_ID = 7L;
    private static final String OWNER_TENANT = "owner-tenant";
    private static final String MEMBER_TENANT = "member-tenant";
    private static final String ORG_ID = "org-1";
    private static final String MEMBER_ROLE = "MEMBER";

    @Mock private DataSourceEnhancedRepositories repositories;
    @Mock private VectorRepository vectorRepository;
    @Mock private DataSourceService dataSourceService;
    @Mock private DatasourceRowEventPublisher rowEventPublisher;
    @Mock private PlanFeatureGate planFeatureGate;

    /** A cloud gate where only {@code allowedTenant} may use vectors. */
    private DataSourceEnhancedService serviceWhereOnly(String allowedTenant) {
        when(planFeatureGate.allows(anyString(), anyList())).thenReturn(false);
        when(planFeatureGate.allows(eq(allowedTenant), anyList())).thenReturn(true);
        when(planFeatureGate.upgradeRequiredFor(anyString(), anyList())).thenReturn("PRO");
        when(planFeatureGate.upgradeRequiredFor(eq(allowedTenant), anyList())).thenReturn(null);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "cloud");
        VectorFeatureGate gate =
                new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env), planFeatureGate);
        return new DataSourceEnhancedService(repositories, vectorRepository, dataSourceService,
                rowEventPublisher, new com.apimarketplace.datasource.crud.service.MediaCellHydrator(new com.apimarketplace.datasource.crud.service.ColumnValueCoercer()), gate, mock(ApplicationEventPublisher.class));
    }

    /** An org table owned by OWNER_TENANT that MEMBER_TENANT may write to through the org. */
    private void givenSharedOrgTable() {
        DataSource dataSource = new DataSource(DATA_SOURCE_ID, OWNER_TENANT, "Docs", null, null,
                Map.of(), null, null, null, OWNER_TENANT, java.util.List.of(), Map.of(),
                null, null, null, ORG_ID);
        when(dataSourceService.getDataSource(DATA_SOURCE_ID)).thenReturn(Optional.of(dataSource));
        when(dataSourceService.canWriteViaOrg(eq(ORG_ID), eq(MEMBER_TENANT), anyString(), eq(MEMBER_ROLE)))
                .thenReturn(true);
        when(repositories.dataSourceExists(DATA_SOURCE_ID, OWNER_TENANT)).thenReturn(true);
        when(repositories.getColumnDefinitions(DATA_SOURCE_ID, OWNER_TENANT)).thenReturn(java.util.List.of());
    }

    @Test
    @DisplayName("a member on a plan WITHOUT vectors may still add one to their paid workspace's table")
    void memberAddsVectorColumnToPaidWorkspaceTable() {
        givenSharedOrgTable();
        DataSourceEnhancedService service = serviceWhereOnly(OWNER_TENANT);

        assertThatCode(() -> service.addColumn(DATA_SOURCE_ID, MEMBER_TENANT, ORG_ID, MEMBER_ROLE,
                "embedding", "vector", null, Map.of("dimension", 8), null))
                .doesNotThrowAnyException();

        // The question asked was about the table's owner. Asking about the caller is the bug.
        verify(planFeatureGate).allows(eq(OWNER_TENANT), anyList());
    }

    @Test
    @DisplayName("a member on a plan WITH vectors may not add one to a workspace that has not bought them")
    void memberCannotAddVectorColumnToUnpaidWorkspaceTable() {
        givenSharedOrgTable();
        DataSourceEnhancedService service = serviceWhereOnly(MEMBER_TENANT);

        assertThatThrownBy(() -> service.addColumn(DATA_SOURCE_ID, MEMBER_TENANT, ORG_ID, MEMBER_ROLE,
                "embedding", "vector", null, Map.of("dimension", 8), null))
                .isInstanceOf(IllegalArgumentException.class)
                // The refusal names the plan the OWNING workspace would need, which is the only
                // upgrade that would actually make the column usable.
                .hasMessageContaining("PRO");
    }

    @Test
    @DisplayName("a non-vector column is never asked about at all")
    void plainColumnIsNotGated() {
        givenSharedOrgTable();
        DataSourceEnhancedService service = serviceWhereOnly(OWNER_TENANT);

        assertThatCode(() -> service.addColumn(DATA_SOURCE_ID, MEMBER_TENANT, ORG_ID, MEMBER_ROLE,
                "title", "text", null, Map.of(), null))
                .doesNotThrowAnyException();
    }
}
