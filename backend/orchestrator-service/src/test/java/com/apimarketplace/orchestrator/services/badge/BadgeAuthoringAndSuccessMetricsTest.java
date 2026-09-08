package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.client.PublicationClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression cover for the two metrics that read far below the truth when the
 * trophies first shipped, both reported from the live grid: "App Crafter" stuck
 * at 1 for an author with a shelf full of applications, and "Green Light" stuck
 * near zero for a workspace whose workflows run all day.
 *
 * <p><b>Scope, stated plainly.</b> The {@code EntityManager} is a mock, so these
 * tests pin what the collector ASKS FOR and how it COMBINES the answers - which
 * sources it consults, which ids it binds, which of two counts wins. They cannot
 * and do not prove the SQL itself is right; that was checked by running both
 * statements against a real Postgres carrying the orchestrator schema.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("badge authoring and success metrics")
class BadgeAuthoringAndSuccessMetricsTest {

    private static final String TENANT = "42";
    private static final UUID NIL = new UUID(0L, 0L);

    @Mock private PublicationClient publicationClient;
    @Mock private AuthClient authClient;
    @Mock private EntityManager entityManager;

    private BadgeStatsCollector collector;

    /** SQL fragment -> the row that statement should answer with. */
    private final Map<String, Object[]> resultsBySqlFragment = new HashMap<>();
    /** Every (name, value) pair bound on any statement, in order. */
    private final List<Map.Entry<String, Object>> boundParameters = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        collector = new BadgeStatsCollector(publicationClient, authClient);
        Field em = BadgeStatsCollector.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(collector, entityManager);

        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Query query = mock(Query.class);
            when(query.setParameter(anyString(), any())).thenAnswer(bind -> {
                boundParameters.add(Map.entry(bind.getArgument(0), bind.getArgument(1)));
                return query;
            });
            Object[] row = resultsBySqlFragment.entrySet().stream()
                    .filter(entry -> sql.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            when(query.getSingleResult()).thenReturn(row);
            return query;
        });
    }

    private void answerAuthoring(long workflows, long applications, long pinned) {
        resultsBySqlFragment.put("FROM orchestrator.workflows",
                new Object[]{workflows, applications, pinned});
    }

    private void answerRunRows(long total, long completed) {
        resultsBySqlFragment.put("FROM orchestrator.workflow_runs",
                new Object[]{total, completed});
    }

    private void answerEpochs(long launches, long activeDays, long nightLaunches, long successful) {
        resultsBySqlFragment.put("FROM orchestrator.workflow_epochs",
                new Object[]{launches, activeDays, nightLaunches, successful});
    }

    @SuppressWarnings("unchecked")
    private List<UUID> boundPublicationIds() {
        return boundParameters.stream()
                .filter(entry -> entry.getKey().equals("ownPublicationIds"))
                .map(entry -> (List<UUID>) entry.getValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ownPublicationIds parameter was bound"));
    }

    private static Map<String, Object> publication(String id) {
        return Map.of("id", id, "status", "ACTIVE", "visibility", "PUBLIC", "useCount", 0);
    }

    // ── App Crafter: an application you built is stamped with your OWN publication ──

    @Test
    @DisplayName("the applications count is scoped by the publications the user owns")
    void applicationCountBindsTheUsersOwnPublicationIds() {
        String mine = "11111111-1111-1111-1111-111111111111";
        String alsoMine = "22222222-2222-2222-2222-222222222222";
        when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT))
                .thenReturn(List.of(publication(mine), publication(alsoMine)));
        answerAuthoring(3, 7, 2);

        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.APPLICATIONS_CREATED));

        // Pre-fix the statement filtered on `source_publication_id IS NULL` alone,
        // which excluded every application its owner had published - the metric
        // that reported 1 for an author with a shelf full of them.
        assertThat(boundPublicationIds())
                .containsExactly(UUID.fromString(mine), UUID.fromString(alsoMine));
        assertThat(stats.get(BadgeMetric.APPLICATIONS_CREATED)).isEqualTo(7);
    }

    @Test
    @DisplayName("publication-service is consulted for the app-maker badge, not only for marketplace ones")
    void applicationsCreatedPullsTheOwnedPublications() {
        when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT)).thenReturn(List.of());
        answerAuthoring(0, 0, 0);

        collector.collect(TENANT, Set.of(BadgeMetric.APPLICATIONS_CREATED));

        assertThat(boundPublicationIds()).isNotEmpty();
    }

    @Test
    @DisplayName("a user who owns no publication still binds a non-empty list, so the SQL stays valid")
    void noPublicationsBindsTheNilSentinel() {
        when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT)).thenReturn(List.of());
        answerAuthoring(4, 0, 0);

        collector.collect(TENANT, Set.of(BadgeMetric.APPLICATIONS_CREATED));

        // SQL has no empty IN (), so an empty list would be a syntax error at
        // runtime - swallowed by the collector, leaving the metric silently at 0.
        assertThat(boundPublicationIds()).containsExactly(NIL);
    }

    @Test
    @DisplayName("a publication id that is not a UUID is dropped rather than binding a bad value")
    void malformedPublicationIdIsDropped() {
        when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT)).thenReturn(List.of(
                Map.of("id", "not-a-uuid", "status", "ACTIVE", "visibility", "PUBLIC", "useCount", 0),
                publication("33333333-3333-3333-3333-333333333333")));
        answerAuthoring(0, 1, 0);

        collector.collect(TENANT, Set.of(BadgeMetric.APPLICATIONS_CREATED));

        assertThat(boundPublicationIds())
                .containsExactly(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    }

    @Test
    @DisplayName("the workflow count is not widened by the same relaxation - installing your own app is not authoring")
    void workflowsCreatedDoesNotNeedPublications() {
        answerAuthoring(9, 0, 0);

        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.WORKFLOWS_CREATED));

        assertThat(stats.get(BadgeMetric.WORKFLOWS_CREATED)).isEqualTo(9);
        verifyNoInteractions(publicationClient);
    }

    // ── Green Light: a reusable-trigger run never reaches status COMPLETED ──

    @Test
    @DisplayName("successful runs are counted from closed epochs, not from terminal run rows")
    void successCountsEpochsNotRunRows() {
        answerRunRows(3, 0);          // three long-lived trigger runs, none terminal
        answerEpochs(940, 60, 12, 900); // that have fired 940 times, 900 cleanly

        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.RUNS_COMPLETED));

        // Pre-fix this read 0: a schedule/webhook workflow settles back to
        // WAITING_TRIGGER after every fire and never reaches COMPLETED, so the
        // reliability family was unreachable for the users running the most.
        assertThat(stats.get(BadgeMetric.RUNS_COMPLETED)).isEqualTo(900);
    }

    @Test
    @DisplayName("terminal run rows still floor the count, for one-shot runs that opened no epoch")
    void terminalRunRowsFloorTheSuccessCount() {
        answerRunRows(40, 31);
        answerEpochs(0, 0, 0, 0);

        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.RUNS_COMPLETED));

        assertThat(stats.get(BadgeMetric.RUNS_COMPLETED)).isEqualTo(31);
    }

    @Test
    @DisplayName("an epoch-query failure degrades to the run-row count instead of zeroing the metric")
    void epochFailureFallsBackToRunRows() {
        answerRunRows(12, 12);
        // No epoch row registered: getSingleResult answers null and the cast in
        // the collector throws, which its own catch swallows.

        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.RUNS_COMPLETED));

        assertThat(stats.get(BadgeMetric.RUNS_COMPLETED)).isEqualTo(12);
        assertThat(stats.get(BadgeMetric.RUNS_LAUNCHED)).isEqualTo(12);
    }

    @Test
    @DisplayName("launches still take the epoch count when it beats the run-row count")
    void launchesPreferEpochs() {
        answerRunRows(3, 0);
        answerEpochs(940, 60, 12, 900);

        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.RUNS_LAUNCHED));

        assertThat(stats.get(BadgeMetric.RUNS_LAUNCHED)).isEqualTo(940);
    }

    @Test
    @DisplayName("the run-row statement runs for the success badge alone, since it floors it")
    void successMetricStillReadsRunRows() {
        answerRunRows(50, 50);
        answerEpochs(10, 2, 0, 1);

        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.RUNS_COMPLETED));

        assertThat(stats.get(BadgeMetric.RUNS_COMPLETED)).isEqualTo(50);
    }
}
