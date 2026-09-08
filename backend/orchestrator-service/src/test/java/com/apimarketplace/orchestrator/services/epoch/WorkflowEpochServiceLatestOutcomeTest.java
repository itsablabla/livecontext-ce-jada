package com.apimarketplace.orchestrator.services.epoch;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.LatestEpochHeaderRow;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService.LatestEpochOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The batch "how did each run's LAST fire end" lookup that badges the notification bell's
 * Triggers tab.
 *
 * <p>It exists as its own read rather than reusing {@code listEpochTimestamps} because that
 * one ships and deserializes one JSONB document PER EPOCH, and a long-lived reusable-trigger
 * run accumulates epochs without bound - a once-a-minute schedule reaches ~10k a week. The
 * bell asks about many runs on every poll, so it reads one header per run.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEpochService - last-fire outcome, batched")
class WorkflowEpochServiceLatestOutcomeTest {

    @Mock private WorkflowEpochRepository repository;
    @Mock private WorkflowStepDataRepository stepDataRepository;

    private WorkflowEpochService service;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final Instant FIRED_AT = Instant.parse("2026-09-06T10:00:00Z");

    @BeforeEach
    void setUp() {
        service = new WorkflowEpochService(repository, mapper, stepDataRepository);
    }

    @Test
    @DisplayName("A closed header is turned into its epoch's own verdict")
    void closedHeaderYieldsTheEpochVerdict() {
        when(repository.getLatestEpochHeaderByRunIds(anyList())).thenReturn(Map.of(
                "run_ok", header(json(state(Set.of("mcp:fetch"), Set.of())), false),
                "run_ko", header(json(state(Set.of("mcp:fetch"), Set.of("mcp:save"))), false)));

        Map<String, LatestEpochOutcome> outcomes = service.getLatestEpochOutcomeByRunIds(
                List.of("run_ok", "run_ko"));

        assertThat(outcomes.get("run_ok").outcome()).isEqualTo("COMPLETED");
        assertThat(outcomes.get("run_ko").outcome()).isEqualTo("FAILED");
        assertThat(outcomes.get("run_ok").active()).isFalse();
        assertThat(outcomes.get("run_ok").startedAt()).isEqualTo(FIRED_AT);
        // Carried through untouched: the caller needs it to tell whose fire this was.
        assertThat(outcomes.get("run_ok").triggerId()).isEqualTo("trigger:nightly");
    }

    @Test
    @DisplayName("An ACTIVE header yields no verdict but says so, so the caller can ask the run")
    void activeHeaderYieldsNoVerdictButFlagsItself() {
        // The state stored on an open epoch is the one written when it OPENED, so it cannot
        // describe an outcome. Its payload is deliberately one that WOULD read COMPLETED if the
        // active flag were ignored (nodes ran, none failed): reporting that is exactly the
        // confident-wrong-answer this guards, and a fixture that ran nothing could not tell the
        // two reasons for a null apart.
        when(repository.getLatestEpochHeaderByRunIds(anyList())).thenReturn(Map.of(
                "run_live", header(json(state(Set.of("trigger:chat", "mcp:fetch"), Set.of())), true)));

        LatestEpochOutcome live = service.getLatestEpochOutcomeByRunIds(List.of("run_live")).get("run_live");

        assertThat(live.outcome()).isNull();
        assertThat(live.active()).isTrue();
        // The same payload, closed, does state an outcome - so the null above is the ACTIVE
        // flag talking, not an empty epoch.
        when(repository.getLatestEpochHeaderByRunIds(anyList())).thenReturn(Map.of(
                "run_live", header(json(state(Set.of("trigger:chat", "mcp:fetch"), Set.of())), false)));
        assertThat(service.getLatestEpochOutcomeByRunIds(List.of("run_live")).get("run_live").outcome())
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("A closed epoch that ran nothing but its trigger also yields no verdict")
    void closedButEmptyEpochYieldsNoVerdict() {
        // The other reason for a null, kept apart from the one above: the trigger completes on
        // every fire, so its completion alone means armed, not ran.
        when(repository.getLatestEpochHeaderByRunIds(anyList())).thenReturn(Map.of(
                "run_armed", header(json(state(Set.of("trigger:chat"), Set.of())), false)));

        LatestEpochOutcome armed = service.getLatestEpochOutcomeByRunIds(List.of("run_armed")).get("run_armed");

        assertThat(armed.outcome()).isNull();
        assertThat(armed.active()).isFalse();
    }

    @Test
    @DisplayName("An unreadable epoch payload degrades to no verdict instead of throwing")
    void unreadablePayloadDegradesToNoVerdict() {
        // The bell is a background poll on every page. A single corrupt / future-shaped
        // document must cost that row its badge, never the whole popover.
        when(repository.getLatestEpochHeaderByRunIds(anyList())).thenReturn(Map.of(
                "run_bad", header("{not json", false)));

        LatestEpochOutcome bad = service.getLatestEpochOutcomeByRunIds(List.of("run_bad")).get("run_bad");

        assertThat(bad).isNotNull();
        assertThat(bad.outcome()).isNull();
    }

    @Test
    @DisplayName("A null run list is handed straight to the repository, which owns the guard")
    void nullRunListIsNotSpecialCasedHere() {
        when(repository.getLatestEpochHeaderByRunIds(null)).thenReturn(Map.of());

        assertThat(service.getLatestEpochOutcomeByRunIds(null)).isEmpty();
    }

    @Test
    @DisplayName("An empty batch costs nothing beyond the repository's own short-circuit")
    void emptyBatchReadsNothingElse() {
        // The empty guard itself lives in the repository, so this asserts what THIS layer owes:
        // no step-row aggregate. listEpochTimestamps pays that per call, and the whole reason
        // this method exists is to not pay it for a popover that badges N runs.
        when(repository.getLatestEpochHeaderByRunIds(List.of())).thenReturn(Map.of());

        assertThat(service.getLatestEpochOutcomeByRunIds(List.of())).isEmpty();
        verify(stepDataRepository, never()).findEpochWorkWindows(anyList());
    }

    @Test
    @DisplayName("A populated batch does not read step rows either - the verdict comes from the header alone")
    void populatedBatchReadsNoStepRows() {
        when(repository.getLatestEpochHeaderByRunIds(anyList())).thenReturn(Map.of(
                "run_ok", header(json(state(Set.of("mcp:fetch"), Set.of())), false)));

        service.getLatestEpochOutcomeByRunIds(List.of("run_ok"));

        verify(stepDataRepository, never()).findEpochWorkWindows(anyList());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static EpochState state(Set<String> completed, Set<String> failed) {
        return new EpochState(completed, failed, Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), FIRED_AT);
    }

    private String json(EpochState state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static LatestEpochHeaderRow header(String epochStateJson, boolean active) {
        return new LatestEpochHeaderRow(4, "trigger:nightly", epochStateJson, active, FIRED_AT,
                active ? null : FIRED_AT.plusSeconds(30));
    }
}
