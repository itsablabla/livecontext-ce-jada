package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spending cap's counter, executed as SQL rather than verified on a mock.
 *
 * <p>Everything else about this feature is unit-tested against mocked
 * repositories, which proves the Java rules and proves nothing at all about the
 * statements. These four statements carry the parts that mocks cannot see: a
 * conditional reset-in-place, a marker that must only ever move forward, and an
 * add that must not reset. A swapped branch here would show up as a cap that
 * quietly never fires, or one that fires a month early.
 *
 * <p><b>This runs on H2 in PostgreSQL mode</b>, which is the profile this module
 * has. It therefore proves the branch LOGIC, not Postgres-specific typing. The
 * typing hazard that H2 would have hidden (Postgres cannot infer the type of a
 * null bind, and this repo shipped that bug once already) was removed at the
 * source instead of tested around: the increment and the reset are each split
 * into a non-null variant and a cumulative variant, so no null timestamp is ever
 * bound. That is why there is no "null period start" case below.
 */
@DataJpaIntegrationTest
class WorkflowBudgetPeriodRepositoryIntegrationTest {

    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private TestEntityManager entityManager;

    private static final String TENANT = "tenant-budget";

    private WorkflowEntity persistWorkflow(String mode, BigDecimal spent, Instant periodStart) {
        WorkflowEntity workflow = new WorkflowEntity(TENANT, "Nightly Digest", "user-a");
        workflow.setId(UUID.randomUUID());
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        workflow.setOrganizationId(TENANT);
        workflow.setBudgetPeriodMode(mode);
        entityManager.persist(workflow);
        entityManager.flush();
        // The period columns are mapped insertable=false/updatable=false (the
        // fence that stops a stale in-memory copy clobbering live spend), so the
        // starting state has to be written the same way production writes it.
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE workflows SET budget_period_spent = ?1, "
                        + "budget_period_started_at = ?2 WHERE id = ?3")
                .setParameter(1, spent)
                .setParameter(2, periodStart)
                .setParameter(3, workflow.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return workflow;
    }

    private BigDecimal spentOf(UUID id) {
        return workflowRepository.findBudgetPeriodSpentById(id).orElseThrow();
    }

    private Instant periodStartOf(UUID id) {
        Object raw = entityManager.getEntityManager()
                .createNativeQuery("SELECT budget_period_started_at FROM workflows WHERE id = ?1")
                .setParameter(1, id)
                .getSingleResult();
        // The driver's temporal type is its own business (H2 hands back an
        // OffsetDateTime here, another may hand back a Timestamp); what this
        // test asserts is the instant, so normalise rather than pin a class.
        if (raw == null) {
            return null;
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (raw instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return (Instant) raw;
    }

    // ─── Accumulating within a live period ───

    @Test
    @DisplayName("adds to the counter while the stored period is still current")
    void addsWithinTheSamePeriod() {
        Instant thisMonth = Instant.parse("2026-09-01T00:00:00Z");
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("3"), thisMonth);

        int rows = workflowRepository.incrementBudgetPeriodSpendWithReset(
                w.getId(), new BigDecimal("1.5"), thisMonth);

        assertThat(rows).isEqualTo(1);
        assertThat(spentOf(w.getId())).isEqualByComparingTo("4.5");
        assertThat(periodStartOf(w.getId())).isEqualTo(thisMonth);
    }

    @Test
    @DisplayName("a workflow that has never spent (NULL period start) starts the period at this settle")
    void nullStoredPeriodStartsFresh() {
        // The migration leaves existing rows with a NULL marker on purpose, so
        // a workflow capped before the feature existed begins counting from now
        // rather than from a figure nobody agreed to.
        Instant thisMonth = Instant.parse("2026-09-01T00:00:00Z");
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("0"), null);

        workflowRepository.incrementBudgetPeriodSpendWithReset(w.getId(), new BigDecimal("2"), thisMonth);

        assertThat(spentOf(w.getId())).isEqualByComparingTo("2");
        assertThat(periodStartOf(w.getId())).isEqualTo(thisMonth);
    }

    // ─── The rollover, which is the whole point of the period ───

    @Test
    @DisplayName("an EXPIRED period is reset in place, so last month's spend cannot block this month")
    void expiredPeriodResetsInPlace() {
        // Reset and add in ONE statement. Doing it as a read-then-write in
        // application code would let two settles crossing the boundary either
        // double-reset (losing the first cost of the new period) or skip the
        // reset entirely.
        Instant lastMonth = Instant.parse("2026-08-01T00:00:00Z");
        Instant thisMonth = Instant.parse("2026-09-01T00:00:00Z");
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("50"), lastMonth);

        workflowRepository.incrementBudgetPeriodSpendWithReset(w.getId(), new BigDecimal("2"), thisMonth);

        // 50 belonged to a period that is over: only the new 2 counts.
        assertThat(spentOf(w.getId())).isEqualByComparingTo("2");
        assertThat(periodStartOf(w.getId())).isEqualTo(thisMonth);
    }

    @Test
    @DisplayName("the marker only moves FORWARD, so a pod with a slow clock cannot wipe the new period")
    void markerNeverMovesBackwards() {
        // Two pods straddling a boundary would otherwise take turns resetting
        // each other's period and the counter would sit near zero for as long
        // as the skew lasted: a cap that never fires. Refusing to move back
        // costs a slight over-count, which errs towards enforcing the cap.
        Instant thisMonth = Instant.parse("2026-09-01T00:00:00Z");
        Instant lastMonth = Instant.parse("2026-08-01T00:00:00Z");
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("4"), thisMonth);

        workflowRepository.incrementBudgetPeriodSpendWithReset(w.getId(), new BigDecimal("1"), lastMonth);

        assertThat(spentOf(w.getId()))
                .as("the late settle is added, not treated as a new period")
                .isEqualByComparingTo("5");
        assertThat(periodStartOf(w.getId()))
                .as("the marker stays on the newer period")
                .isEqualTo(thisMonth);
    }

    // ─── Cumulative: the mode that must never reset ───

    @Test
    @DisplayName("cumulative adds without touching the marker, however old the stored period is")
    void cumulativeAddsAndNeverResets() {
        Instant longAgo = Instant.parse("2024-01-01T00:00:00Z");
        WorkflowEntity w = persistWorkflow("cumulative", new BigDecimal("6"), longAgo);

        int rows = workflowRepository.incrementBudgetPeriodSpendCumulative(w.getId(), new BigDecimal("1"));

        assertThat(rows).isEqualTo(1);
        assertThat(spentOf(w.getId()))
                .as("a lifetime total keeps accumulating; a reset here would uncap the workflow")
                .isEqualByComparingTo("7");
        assertThat(periodStartOf(w.getId())).isEqualTo(longAgo);
    }

    @Test
    @DisplayName("a deleted workflow updates no rows rather than throwing")
    void unknownWorkflowUpdatesNothing() {
        assertThat(workflowRepository.incrementBudgetPeriodSpendWithReset(
                UUID.randomUUID(), new BigDecimal("1"), Instant.parse("2026-09-01T00:00:00Z"))).isZero();
        assertThat(workflowRepository.incrementBudgetPeriodSpendCumulative(
                UUID.randomUUID(), new BigDecimal("1"))).isZero();
    }

    // ─── Restarting the period when the user changes the terms of the cap ───

    @Test
    @DisplayName("resetting stamps a fresh period and zeroes the counter")
    void resetAtStartsAFreshPeriod() {
        // What saves a user from being refused the instant they first set a cap,
        // judged against spend from before it existed.
        Instant thisMonth = Instant.parse("2026-09-01T00:00:00Z");
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("50"),
                thisMonth.minus(3, ChronoUnit.DAYS));

        int rows = workflowRepository.resetBudgetPeriodAt(w.getId(), thisMonth);

        assertThat(rows).isEqualTo(1);
        assertThat(spentOf(w.getId())).isEqualByComparingTo("0");
        assertThat(periodStartOf(w.getId())).isEqualTo(thisMonth);
    }

    @Test
    @DisplayName("resetting a cumulative cap clears the marker a lifetime cap has no use for")
    void resetCumulativeClearsTheMarker() {
        WorkflowEntity w = persistWorkflow("cumulative", new BigDecimal("500"),
                Instant.parse("2026-08-01T00:00:00Z"));

        int rows = workflowRepository.resetBudgetPeriodCumulative(w.getId());

        assertThat(rows).isEqualTo(1);
        assertThat(spentOf(w.getId())).isEqualByComparingTo("0");
        assertThat(periodStartOf(w.getId())).isNull();
    }

    @Test
    @DisplayName("the counter survives an ordinary save: the entity fence keeps a stale copy out")
    void ordinarySaveCannotClobberTheCounter() {
        // budget_period_spent / _started_at are mapped insertable=false,
        // updatable=false precisely so that renaming a workflow cannot write
        // back an in-memory figure that concurrent settles have moved on from.
        Instant thisMonth = Instant.parse("2026-09-01T00:00:00Z");
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("7"), thisMonth);

        WorkflowEntity loaded = workflowRepository.findById(w.getId()).orElseThrow();
        loaded.setName("Renamed");
        workflowRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        assertThat(spentOf(w.getId())).isEqualByComparingTo("7");
        assertThat(periodStartOf(w.getId())).isEqualTo(thisMonth);
    }

    // ─── The state projection: the one read both enforcement points depend on ───

    private WorkflowRunEntity persistRun(WorkflowEntity workflow, String runIdPublic, Boolean editorFlag) {
        Map<String, Object> metadata = new HashMap<>();
        if (editorFlag != null) {
            metadata.put("__editorRun__", editorFlag);
        }
        // 4th arg is the TRIGGER PAYLOAD, 5th is the metadata the flag lives in.
        WorkflowRunEntity run = new WorkflowRunEntity(
                workflow, workflow.getTenantId(), runIdPublic, Map.of("source", "test"), metadata, "user-a");
        run.setOrganizationId(workflow.getOrganizationId());
        entityManager.persist(run);
        entityManager.flush();
        entityManager.clear();
        return run;
    }

    private void pin(WorkflowEntity workflow, WorkflowRunEntity run) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE workflows SET production_run_id = ?1 WHERE id = ?2")
                .setParameter(1, run.getId())
                .setParameter(2, workflow.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("reads the cap, the period and BOTH identity flags in one row")
    void projectionReadsTheWholeState() {
        Instant thisMonth = Instant.parse("2026-09-01T00:00:00Z");
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("4"), thisMonth);
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE workflows SET budget_credits = 10 WHERE id = ?1")
                .setParameter(1, w.getId()).executeUpdate();
        WorkflowRunEntity run = persistRun(w, "run-proj-1", null);
        pin(w, run);

        Optional<WorkflowBudgetState> state = runRepository.findBudgetStateByRunIdPublic("run-proj-1");

        assertThat(state).isPresent();
        assertThat(state.get().workflowId()).isEqualTo(w.getId());
        assertThat(state.get().budgetCredits()).isEqualByComparingTo("10");
        assertThat(state.get().periodMode()).isEqualTo("monthly");
        assertThat(state.get().periodSpent()).isEqualByComparingTo("4");
        assertThat(state.get().periodStartedAt()).isEqualTo(thisMonth);
        assertThat(state.get().productionRun()).isTrue();
        assertThat(state.get().editorRun()).isFalse();
    }

    @Test
    @DisplayName("an unpinned workflow yields productionRun=false, NOT a null that would NPE on unboxing")
    void unpinnedWorkflowProjectsFalseNotNull() {
        // production_run_id is NULL here. Comparing a column to NULL yields NULL
        // in SQL, and a NULL mapped onto the record's primitive boolean would
        // throw on unboxing - on the read that every agent call performs. The
        // statement guards the comparison for exactly that reason.
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("0"), null);
        persistRun(w, "run-proj-2", null);

        Optional<WorkflowBudgetState> state = runRepository.findBudgetStateByRunIdPublic("run-proj-2");

        assertThat(state).isPresent();
        assertThat(state.get().productionRun()).isFalse();
        assertThat(state.get().editorRun()).isFalse();
        assertThat(state.get().appliesToRun())
                .as("neither production nor a builder fire: the cap governs it")
                .isTrue();
    }

    @Test
    @DisplayName("reads __editorRun__ out of the run's JSONB metadata, which is why the query is native")
    void projectionReadsTheEditorFlagFromMetadata() {
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("0"), null);
        persistRun(w, "run-proj-3", Boolean.TRUE);

        Optional<WorkflowBudgetState> state = runRepository.findBudgetStateByRunIdPublic("run-proj-3");

        assertThat(state).isPresent();
        assertThat(state.get().editorRun()).isTrue();
        assertThat(state.get().appliesToRun())
                .as("a builder test fire is the one run the cap leaves alone")
                .isFalse();
    }

    @Test
    @DisplayName("a PROMOTED editor run reads as production, because pinning never strips the flag")
    void promotedEditorRunProjectsBothFlags() {
        WorkflowEntity w = persistWorkflow("monthly", new BigDecimal("0"), null);
        WorkflowRunEntity run = persistRun(w, "run-proj-4", Boolean.TRUE);
        pin(w, run);

        Optional<WorkflowBudgetState> state = runRepository.findBudgetStateByRunIdPublic("run-proj-4");

        assertThat(state).isPresent();
        assertThat(state.get().productionRun()).isTrue();
        assertThat(state.get().editorRun()).isTrue();
        assertThat(state.get().appliesToRun())
                .as("the FK has to win, or every pinned workflow's production run is exempt")
                .isTrue();
    }

    @Test
    @DisplayName("an unknown run id reads empty rather than throwing")
    void unknownRunProjectsEmpty() {
        assertThat(runRepository.findBudgetStateByRunIdPublic("no-such-run")).isEmpty();
    }
}
