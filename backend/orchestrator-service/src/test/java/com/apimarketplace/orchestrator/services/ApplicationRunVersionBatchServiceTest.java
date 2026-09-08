package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.controllers.dto.ApplicationRunVersionSummary;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Assembly contract for the Applications-page batch enrichment: per workflow, {applicationRunId,
 * lastExecutedAt (lastFireAt ?? startedAt), pinnedVersion}, resolved from three batched queries
 * instead of two HTTP calls per card. The pinned-version half is filtered to the caller's active
 * workspace via the SAME ScopeGuard.isInStrictScope predicate the per-card /versions endpoint used.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationRunVersionBatchServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowEpochRepository epochRepository;

    @InjectMocks private ApplicationRunVersionBatchService service;

    private static final String ORG = "org-1";
    private static final String USER = "user-1";

    private static WorkflowRunEntity appRun(UUID wfId, String runIdPublic, Instant startedAt) {
        WorkflowEntity wf = org.mockito.Mockito.mock(WorkflowEntity.class);
        when(wf.getId()).thenReturn(wfId);
        WorkflowRunEntity run = org.mockito.Mockito.mock(WorkflowRunEntity.class);
        when(run.getWorkflow()).thenReturn(wf);
        when(run.getRunIdPublic()).thenReturn(runIdPublic);
        lenient().when(run.getStartedAt()).thenReturn(startedAt); // only read on the no-epoch fallback
        return run;
    }

    /**
     * A scope-projection row:
     * [id, pinnedVersion, tenantId, organizationId, cap, periodMode, periodSpent, periodStart].
     * The four budget columns ride along so an application card can show its
     * spend without a second request.
     */
    private static Object[] scopeRow(UUID id, Integer pinned, String tenantId, String orgId) {
        return scopeRow(id, pinned, tenantId, orgId, null, null, null, null);
    }

    private static Object[] scopeRow(UUID id, Integer pinned, String tenantId, String orgId,
                                     java.math.BigDecimal cap, String periodMode,
                                     java.math.BigDecimal periodSpent, Instant periodStart) {
        return new Object[]{id, pinned, tenantId, orgId, cap, periodMode, periodSpent, periodStart};
    }

    @Test
    @DisplayName("empty input short-circuits to an empty map without touching any repository")
    void emptyInput() {
        assertThat(service.resolve(List.of(), ORG, USER)).isEmpty();
        verify(runRepository, never()).findApplicationRunsBatch(anyCollection());
        verify(workflowRepository, never()).findPinnedVersionScopeRows(anyCollection());
        verify(epochRepository, never()).getLatestEpochStartedAtByRunIds(anyList());
    }

    @Test
    @DisplayName("a run with a fired epoch reports lastFireAt (not startedAt) + the in-scope pinned version")
    void runWithEpochUsesLastFire() {
        UUID wf = UUID.randomUUID();
        Instant started = Instant.parse("2026-06-01T00:00:00Z");
        Instant lastFire = Instant.parse("2026-06-05T00:00:00Z");
        WorkflowRunEntity run = appRun(wf, "run-1", started); // build the mock BEFORE the outer stub
        when(runRepository.findApplicationRunsBatch(List.of(wf))).thenReturn(List.of(run));
        when(epochRepository.getLatestEpochStartedAtByRunIds(List.of("run-1")))
                .thenReturn(Map.of("run-1", lastFire));
        when(workflowRepository.findPinnedVersionScopeRows(List.of(wf)))
                .thenReturn(List.<Object[]>of(scopeRow(wf, 3, "owner", ORG))); // in the caller's org

        ApplicationRunVersionSummary s = service.resolve(List.of(wf), ORG, USER).get(wf);
        assertThat(s.applicationRunId()).isEqualTo("run-1");
        assertThat(s.lastExecutedAt()).isEqualTo(lastFire); // epoch fire wins over startedAt
        assertThat(s.pinnedVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("a run with NO fired epoch falls back to the run's startedAt")
    void runWithoutEpochFallsBackToStartedAt() {
        UUID wf = UUID.randomUUID();
        Instant started = Instant.parse("2026-06-01T00:00:00Z");
        WorkflowRunEntity run = appRun(wf, "run-1", started); // build the mock BEFORE the outer stub
        when(runRepository.findApplicationRunsBatch(List.of(wf))).thenReturn(List.of(run));
        when(epochRepository.getLatestEpochStartedAtByRunIds(List.of("run-1")))
                .thenReturn(Map.of()); // no epoch fired yet
        when(workflowRepository.findPinnedVersionScopeRows(List.of(wf)))
                .thenReturn(List.<Object[]>of(scopeRow(wf, null, "owner", ORG)));

        ApplicationRunVersionSummary s = service.resolve(List.of(wf), ORG, USER).get(wf);
        assertThat(s.lastExecutedAt()).isEqualTo(started);
        assertThat(s.pinnedVersion()).isNull(); // unpinned (Inactive)
    }

    @Test
    @DisplayName("a workflow with a pinned version but NO application run emits null run fields (badge still drawn)")
    void pinnedButNoRun() {
        UUID wf = UUID.randomUUID();
        when(runRepository.findApplicationRunsBatch(List.of(wf))).thenReturn(List.of());
        when(workflowRepository.findPinnedVersionScopeRows(List.of(wf)))
                .thenReturn(List.<Object[]>of(scopeRow(wf, 7, "owner", ORG)));

        Map<UUID, ApplicationRunVersionSummary> out = service.resolve(List.of(wf), ORG, USER);
        assertThat(out).containsKey(wf);
        ApplicationRunVersionSummary s = out.get(wf);
        assertThat(s.applicationRunId()).isNull();
        assertThat(s.lastExecutedAt()).isNull();
        assertThat(s.pinnedVersion()).isEqualTo(7);
        // No runs -> the epoch batch is skipped entirely (not even an empty-list call).
        verify(epochRepository, never()).getLatestEpochStartedAtByRunIds(anyList());
    }

    @Test
    @DisplayName("personal caller (blank orgId): an own org-less row is in scope; another user's is not")
    void personalScopeOwnOrglessRowOnly() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        when(runRepository.findApplicationRunsBatch(List.of(mine, theirs))).thenReturn(List.of());
        when(workflowRepository.findPinnedVersionScopeRows(List.of(mine, theirs)))
                .thenReturn(List.of(
                        scopeRow(mine, 2, USER, null),    // my own, org-less -> in personal scope
                        scopeRow(theirs, 9, "user-2", null) // another user's org-less -> NOT in scope
                ));

        Map<UUID, ApplicationRunVersionSummary> out = service.resolve(List.of(mine, theirs), "  ", USER);

        assertThat(out).containsKey(mine);
        assertThat(out.get(mine).pinnedVersion()).isEqualTo(2);
        assertThat(out).doesNotContainKey(theirs); // out-of-scope + no run -> absent
    }

    @Test
    @DisplayName("org caller: a workflow in a DIFFERENT org reports no pinned version (no cross-workspace leak)")
    void orgScopeExcludesOtherOrg() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        when(runRepository.findApplicationRunsBatch(List.of(mine, theirs))).thenReturn(List.of());
        when(workflowRepository.findPinnedVersionScopeRows(List.of(mine, theirs)))
                .thenReturn(List.of(
                        scopeRow(mine, 3, "owner", ORG),       // in my org -> included
                        scopeRow(theirs, 9, "owner", "org-2")  // other org -> excluded
                ));

        Map<UUID, ApplicationRunVersionSummary> out = service.resolve(List.of(mine, theirs), ORG, USER);

        assertThat(out.get(mine).pinnedVersion()).isEqualTo(3);
        assertThat(out).doesNotContainKey(theirs);
    }

    // ─── The positional projection. Both budget figures are BigDecimal, so a
    //     swapped index is invisible to the type system: only distinct VALUES,
    //     asserted per field, can catch it. Every existing call site passed
    //     nulls for these four columns, which meant the mapping this card
    //     depends on was covered by nothing at all. ───

    @Test
    @DisplayName("maps each budget column to its own field, with values that expose a swap")
    void budgetColumnsMapPositionallyToTheRightFields() {
        UUID wf = UUID.randomUUID();
        Instant started = Instant.parse("2026-09-05T00:00:00Z");
        Instant periodStart = com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                .periodStart("weekly", Instant.now());
        WorkflowRunEntity run = appRun(wf, "run-b1", started);
        when(runRepository.findApplicationRunsBatch(List.of(wf))).thenReturn(List.of(run));
        when(epochRepository.getLatestEpochStartedAtByRunIds(List.of("run-b1"))).thenReturn(Map.of());
        when(workflowRepository.findPinnedVersionScopeRows(anyCollection()))
                .thenReturn(List.<Object[]>of(scopeRow(wf, 4, "owner", ORG,
                        new java.math.BigDecimal("250"), "weekly",
                        new java.math.BigDecimal("37"), periodStart)));

        ApplicationRunVersionSummary summary = service.resolve(List.of(wf), ORG, USER).get(wf);

        assertThat(summary.budgetCredits())
                .as("the cap, not the spend")
                .isEqualByComparingTo("250");
        assertThat(summary.budgetPeriodSpent())
                .as("the spend, not the cap")
                .isEqualByComparingTo("37");
        assertThat(summary.budgetPeriodMode()).isEqualTo("weekly");
        // The date the allowance starts again, from the same rule the counter
        // resets on. Untested, the mapper can simply stop filling it and the
        // application card's popover loses its only concrete promise.
        assertThat(summary.budgetPeriodResetsAt())
                .isEqualTo(com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                        .nextPeriodStart("weekly", Instant.now()));
    }

    @Test
    @DisplayName("rolls an expired period over server-side, so a card cannot show last month as this month")
    void expiredPeriodRollsOverBeforeItReachesTheCard() {
        // The stored figure is only reset when the next cost is recorded, so a
        // workflow that stopped spending keeps last period's number in the
        // column indefinitely. Shipping it raw would show a card sitting at its
        // cap for a workflow that is free to run.
        UUID wf = UUID.randomUUID();
        Instant started = Instant.parse("2026-09-05T00:00:00Z");
        WorkflowRunEntity run = appRun(wf, "run-b2", started);
        when(runRepository.findApplicationRunsBatch(List.of(wf))).thenReturn(List.of(run));
        when(epochRepository.getLatestEpochStartedAtByRunIds(List.of("run-b2"))).thenReturn(Map.of());
        when(workflowRepository.findPinnedVersionScopeRows(anyCollection()))
                .thenReturn(List.<Object[]>of(scopeRow(wf, 4, "owner", ORG,
                        new java.math.BigDecimal("100"), "monthly",
                        new java.math.BigDecimal("100"),
                        Instant.now().minus(70, java.time.temporal.ChronoUnit.DAYS))));

        ApplicationRunVersionSummary summary = service.resolve(List.of(wf), ORG, USER).get(wf);

        assertThat(summary.budgetPeriodSpent()).isEqualByComparingTo("0");
        assertThat(summary.budgetCredits()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("an unknown period mode reports as monthly, never as the never-resets mode")
    void unknownModeNormalisesToMonthly() {
        // Falling back to cumulative would turn a typo into a lifetime cap, and
        // the workflow would stop for good rather than for a month.
        UUID wf = UUID.randomUUID();
        WorkflowRunEntity run = appRun(wf, "run-b3", Instant.parse("2026-09-05T00:00:00Z"));
        when(runRepository.findApplicationRunsBatch(List.of(wf))).thenReturn(List.of(run));
        when(epochRepository.getLatestEpochStartedAtByRunIds(List.of("run-b3"))).thenReturn(Map.of());
        when(workflowRepository.findPinnedVersionScopeRows(anyCollection()))
                .thenReturn(List.<Object[]>of(scopeRow(wf, 1, "owner", ORG,
                        new java.math.BigDecimal("10"), "quarterly",
                        new java.math.BigDecimal("2"), Instant.now())));

        assertThat(service.resolve(List.of(wf), ORG, USER).get(wf).budgetPeriodMode())
                .isEqualTo("monthly");
    }
}
