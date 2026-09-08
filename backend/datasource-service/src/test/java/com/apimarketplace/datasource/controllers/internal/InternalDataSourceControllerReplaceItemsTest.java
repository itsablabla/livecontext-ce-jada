package com.apimarketplace.datasource.controllers.internal;

import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceItem;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.datasource.services.DataSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@code POST /{id}/items/replace} - the atomic wipe-and-refill that backs the
 * publication "reset the application data to its template" action.
 *
 * <p>Two properties matter and neither is visible from the happy path alone: the ownership
 * gate must be the SAME one {@code bulk-insert} enforces (a destructive endpoint may not be
 * the weak one), and the delete must happen BEFORE the inserts inside a single call, so no
 * reader can observe a half-replaced table.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalDataSourceController - replace items")
class InternalDataSourceControllerReplaceItemsTest {

    private static final long DS_ID = 42L;

    @Mock private DataSourceService dataSourceService;
    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private CrudExecutorService crudExecutorService;

    private InternalDataSourceController controller;

    @BeforeEach
    void setUp() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        controller = new InternalDataSourceController(dataSourceService, dataSourceRepository, dataSourceItemRepository, crudExecutorService, new com.apimarketplace.datasource.crud.service.MediaCellHydrator(new com.apimarketplace.datasource.crud.service.ColumnValueCoercer()), new ObjectMapper(), new com.apimarketplace.datasource.services.VectorFeatureGate(
                        new com.apimarketplace.common.web.AppEditionProvider(env), null), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private DataSource dataSourceOwnedBy(String ownerTenant, String org) {
        return new DataSource(DS_ID, ownerTenant, "Leads", null, DataSourceType.INLINE,
                Map.of(), DataSourceStatus.ACTIVE, null, null, ownerTenant,
                List.of(), null, null, null, null, org);
    }

    @Test
    @DisplayName("Deletes the existing rows BEFORE inserting the new ones (no half-replaced table is ever observable)")
    void deletesBeforeInserting() {
        when(dataSourceService.findByIdAndOrganizationIdStrict((int) DS_ID, "org-A"))
                .thenReturn(Optional.of(dataSourceOwnedBy("tenant-1", "org-A")));

        ResponseEntity<Integer> response = controller.replaceItems(DS_ID,
                List.of(Map.of("data", Map.of("x", 1))), "tenant-1", "org-A");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(1);
        InOrder order = inOrder(dataSourceItemRepository);
        order.verify(dataSourceItemRepository).deleteByDataSourceId(DS_ID);
        order.verify(dataSourceItemRepository).save(any());
    }

    @Test
    @DisplayName("Stamps the new rows with the DS OWNER's tenant, not the caller's")
    void stampsRowsWithTheOwnerTenant() {
        // The caller here is a different tenant in the same workspace. Rows stamped with the
        // CALLER's tenant would be invisible to every owner-tenant-scoped read (V333).
        when(dataSourceService.findByIdAndOrganizationIdStrict((int) DS_ID, "org-A"))
                .thenReturn(Optional.of(dataSourceOwnedBy("owner-tenant", "org-A")));

        controller.replaceItems(DS_ID, List.of(Map.of("data", Map.of("x", 1))), "caller-tenant", "org-A");

        ArgumentCaptor<DataSourceItem> saved = ArgumentCaptor.forClass(DataSourceItem.class);
        verify(dataSourceItemRepository).save(saved.capture());
        assertThat(saved.getValue().tenantId()).isEqualTo("owner-tenant");
    }

    @Test
    @DisplayName("An empty payload wipes the table: the template shipped that table empty")
    void emptyPayloadWipesTheTable() {
        when(dataSourceService.findByIdAndOrganizationIdStrict((int) DS_ID, "org-A"))
                .thenReturn(Optional.of(dataSourceOwnedBy("tenant-1", "org-A")));

        ResponseEntity<Integer> response = controller.replaceItems(DS_ID, List.of(), "tenant-1", "org-A");

        assertThat(response.getBody()).isZero();
        verify(dataSourceItemRepository).deleteByDataSourceId(DS_ID);
        verify(dataSourceItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rejects a datasource outside the caller's workspace with 404 and deletes NOTHING")
    void rejectsCrossOrgDatasourceWithoutDeleting() {
        when(dataSourceService.findByIdAndOrganizationIdStrict((int) DS_ID, "org-X"))
                .thenReturn(Optional.empty());

        ResponseEntity<Integer> response = controller.replaceItems(DS_ID,
                List.of(Map.of("data", Map.of("x", 1))), "tenant-1", "org-X");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The destructive half must not run on the rejected path - a delete followed by a
        // 404 would silently wipe another workspace's table.
        verify(dataSourceItemRepository, never()).deleteByDataSourceId(anyLong());
        verify(dataSourceItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("Without an org header, still requires tenant ownership (dropping the header is not a privilege escalation)")
    void withoutOrgHeaderStillRequiresTenantOwnership() {
        when(dataSourceService.findByIdAndTenantId((int) DS_ID, "tenant-1")).thenReturn(Optional.empty());

        ResponseEntity<Integer> response = controller.replaceItems(DS_ID,
                List.of(Map.of("data", Map.of("x", 1))), "tenant-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(dataSourceItemRepository, never()).deleteByDataSourceId(anyLong());
    }

    @Test
    @DisplayName("Without an org header, a tenant-owned datasource is replaced and stamped with that tenant")
    void withoutOrgHeaderTenantOwnedDatasourceIsReplaced() {
        when(dataSourceService.findByIdAndTenantId((int) DS_ID, "tenant-1"))
                .thenReturn(Optional.of(dataSourceOwnedBy("tenant-1", null)));

        ResponseEntity<Integer> response = controller.replaceItems(DS_ID,
                List.of(Map.of("data", Map.of("x", 1)), Map.of("data", Map.of("y", 2))), "tenant-1", null);

        assertThat(response.getBody()).isEqualTo(2);
        verify(dataSourceItemRepository).deleteByDataSourceId(DS_ID);
        verify(dataSourceItemRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Carries the row priority through, and defaults it to null when absent")
    void carriesRowPriorityThrough() {
        when(dataSourceService.findByIdAndOrganizationIdStrict((int) DS_ID, "org-A"))
                .thenReturn(Optional.of(dataSourceOwnedBy("tenant-1", "org-A")));

        controller.replaceItems(DS_ID, List.of(
                Map.of("data", Map.of("x", 1), "priority", 5),
                Map.of("data", Map.of("y", 2))), "tenant-1", "org-A");

        ArgumentCaptor<DataSourceItem> saved = ArgumentCaptor.forClass(DataSourceItem.class);
        verify(dataSourceItemRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).priority()).isEqualTo(5);
        assertThat(saved.getAllValues().get(1).priority()).isNull();
    }
}
