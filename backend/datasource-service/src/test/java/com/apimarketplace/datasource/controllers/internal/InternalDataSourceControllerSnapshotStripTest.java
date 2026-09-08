package com.apimarketplace.datasource.controllers.internal;

import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.services.VectorFeatureGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshot-clone strip wiring - {@code POST /create-from-snapshot} is the path
 * a marketplace acquisition takes; a snapshot published from a self-hosted
 * deployment can carry vector columns that managed cloud must not recreate.
 * The strip must cover BOTH the mappingSpec (the column itself) AND
 * columnOrder (a stripped name left there renders as a ghost column).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalDataSourceController - snapshot vector strip")
class InternalDataSourceControllerSnapshotStripTest {

    @Mock private DataSourceService dataSourceService;
    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private CrudExecutorService crudExecutorService;

    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);

    private InternalDataSourceController controller(String edition) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", edition);
        return new InternalDataSourceController(dataSourceService, dataSourceRepository, dataSourceItemRepository, crudExecutorService, new com.apimarketplace.datasource.crud.service.MediaCellHydrator(new com.apimarketplace.datasource.crud.service.ColumnValueCoercer()), new ObjectMapper(), new VectorFeatureGate(new AppEditionProvider(env), null), eventPublisher);
    }

    /** The typed twin of vectorSnapshot()'s mappingSpec, for fixtures that need a DataSource record. */
    private static Map<String, com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec> vectorMappingSpec() {
        Map<String, com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec> mapping = new LinkedHashMap<>();
        mapping.put("embedding", new com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec(
                "data.embedding", com.apimarketplace.datasource.domain.ColumnType.VECTOR,
                com.apimarketplace.datasource.domain.ColumnStructure.SCALAR,
                Map.of(), Map.of("dimension", 1536, "metric", "cosine")));
        return mapping;
    }

    private static Map<String, Object> vectorSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "cloned-docs");
        snapshot.put("sourceType", "INLINE");
        snapshot.put("organizationId", "org-1");
        snapshot.put("columnOrder", List.of(
                Map.of("field", "title", "order", 0),
                Map.of("field", "embedding", "order", 1)));
        snapshot.put("mappingSpec", Map.of(
                "title", Map.of("path", "data.title", "type", "text", "structure", "SCALAR"),
                "embedding", Map.of("path", "data.embedding", "type", "vector", "structure", "SCALAR",
                        "display", Map.of("dimension", 1536))));
        return snapshot;
    }

    @Test
    @DisplayName("managed cloud strips the vector column from BOTH mappingSpec and columnOrder; the rest of the clone survives")
    void cloudStripsVectorFromSpecAndColumnOrder() {
        when(dataSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = controller("cloud").createFromSnapshot(vectorSnapshot(), "tenant-1", "org-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<DataSource> captor = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceRepository).save(captor.capture());
        DataSource saved = captor.getValue();
        assertThat(saved.mappingSpec().keySet()).containsExactly("title");
        assertThat(saved.columnOrder())
                .extracting(entry -> entry.get("field"))
                .containsExactly("title");
    }

    @Test
    @DisplayName("self-hosted CE clones the vector column untouched")
    void ceKeepsVectorColumn() {
        when(dataSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = controller("ce").createFromSnapshot(vectorSnapshot(), "tenant-1", "org-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<DataSource> captor = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceRepository).save(captor.capture());
        DataSource saved = captor.getValue();
        assertThat(saved.mappingSpec().keySet()).containsExactlyInAnyOrder("title", "embedding");
        assertThat(saved.columnOrder()).hasSize(2);
    }

    /**
     * This path saved through the repository and never published the event that
     * builds the per-datasource HNSW index, so a cloned vector table had rows but
     * no index and every similarity search on it sequential-scanned, silently, at
     * the measured 153x cost. Dimension and metric travel in the column's display
     * config, which is where the event reads them.
     */
    @Test
    @DisplayName("REGRESSION: a clone that keeps the vector column publishes the index-build event with the column's dimension and metric")
    void cloneThatKeepsVectorColumnPublishesBuildEvent() {
        org.mockito.Mockito.when(dataSourceRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> ((com.apimarketplace.datasource.domain.DataSourceModels.DataSource) inv.getArgument(0)).withId(99L));

        controller("ce").createFromSnapshot(vectorSnapshot(), "tenant-1", "org-1");

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(eventPublisher).publishEvent(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .isInstanceOf(com.apimarketplace.datasource.events.VectorColumnCreatedEvent.class);
        var event = (com.apimarketplace.datasource.events.VectorColumnCreatedEvent) captor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.dataSourceId()).isEqualTo(99L);
        org.assertj.core.api.Assertions.assertThat(event.dimension()).isEqualTo(1536);
        org.assertj.core.api.Assertions.assertThat(event.metric()).isEqualTo("cosine");
    }

    @Test
    @DisplayName("a clone that stripped the vector column publishes nothing: there is no column to index")
    void cloneThatStrippedVectorColumnPublishesNothing() {
        org.mockito.Mockito.when(dataSourceRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> ((com.apimarketplace.datasource.domain.DataSourceModels.DataSource) inv.getArgument(0)).withId(99L));

        controller("cloud").createFromSnapshot(vectorSnapshot(), "tenant-1", "org-1");

        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.never())
                .publishEvent(org.mockito.ArgumentMatchers.any());
    }

    /**
     * This path deletes through the repository and never reaches
     * DataSourceService.deleteDataSource, so without its own publish every vector
     * table owned by a deleted workflow left its HNSW index behind, and the
     * NUMBER of those indexes is the design's operational ceiling.
     */
    @Test
    @DisplayName("REGRESSION: deleting a workflow's tables publishes one index-drop event per vector table, none for plain ones")
    void deleteByWorkflowPublishesDropPerVectorTable() {
        java.util.UUID workflowId = java.util.UUID.randomUUID();
        var vectorTable = new com.apimarketplace.datasource.domain.DataSourceModels.DataSource(
                7L, "tenant-1", "Vectors", null,
                com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType.INLINE, Map.of(),
                com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus.ACTIVE, null, null, "tenant-1",
                java.util.List.of(), vectorMappingSpec(), null, null, null, "org-1");
        var plainTable = new com.apimarketplace.datasource.domain.DataSourceModels.DataSource(
                8L, "tenant-1", "Plain", null,
                com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType.INLINE, Map.of(),
                com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus.ACTIVE, null, null, "tenant-1",
                java.util.List.of(), Map.of(), null, null, null, "org-1");
        org.mockito.Mockito.when(dataSourceRepository.findBySourceWorkflowIdAndOrganizationId(workflowId, "org-1"))
                .thenReturn(java.util.List.of(vectorTable, plainTable));

        controller("ce").deleteByWorkflow(workflowId, "tenant-1", "org-1");

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(eventPublisher).publishEvent(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .isInstanceOf(com.apimarketplace.datasource.events.VectorDataSourceDeletedEvent.class);
        org.assertj.core.api.Assertions.assertThat(
                ((com.apimarketplace.datasource.events.VectorDataSourceDeletedEvent) captor.getValue()).dataSourceId())
                .isEqualTo(7L);
        // Publish AFTER the delete: an index dropped before its rows are gone would
        // race a live table.
        var order = org.mockito.Mockito.inOrder(dataSourceRepository, eventPublisher);
        order.verify(dataSourceRepository).deleteBySourceWorkflowIdAndOrganizationId(workflowId, "org-1");
        order.verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
    }
}
