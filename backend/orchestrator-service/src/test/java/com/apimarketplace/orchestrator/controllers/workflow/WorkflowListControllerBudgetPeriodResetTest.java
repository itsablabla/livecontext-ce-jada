package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod;
import com.apimarketplace.orchestrator.services.folder.WorkflowFolderService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changing the TERMS of a spending cap starts its period over.
 *
 * <p>Both cases this covers were traps rather than settings. Setting a first cap
 * judged it against spend accumulated while no cap existed, so a workflow that
 * had quietly spent 50 credits this month was refused the instant its owner
 * prudently capped it at 10, with no way to clear the counter and no fire
 * possible until the month rolled over. Changing the cadence re-filed an
 * existing figure under a period it does not belong to, in both directions: the
 * same stored number means "this month" under monthly and "for all time" under
 * cumulative.
 *
 * <p>And the case that must NOT reset is the one people do every day: a rename
 * must never zero anybody's spending counter.
 */
@DisplayName("WorkflowListController - restarting the cap's period when its terms change")
class WorkflowListControllerBudgetPeriodResetTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String USER = "98";

    private WorkflowRepository workflowRepository;
    private WorkflowListController controller;
    private WorkflowEntity workflow;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        workflow = new WorkflowEntity(USER, "Nightly Digest", USER);
        workflow.setId(WORKFLOW_ID);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        controller = new WorkflowListController(
                workflowRepository,
                mock(WorkflowRunRepository.class),
                mock(SignalWaitRepository.class),
                mock(TriggerClient.class),
                mock(PublicationClient.class),
                mock(WorkflowManagementService.class),
                mock(WorkflowBoardService.class),
                mock(OrgAccessGuard.class),
                mock(WorkflowFolderService.class));
    }

    private ResponseEntity<?> update(Map<String, Object> body) {
        return controller.updateWorkflowMetadata(WORKFLOW_ID, USER, null, null, new HashMap<>(body));
    }

    @Test
    @DisplayName("setting a FIRST cap starts a fresh period, so old spend cannot block it immediately")
    void firstCapStartsAFreshPeriod() {
        workflow.setBudgetPeriodMode("monthly");

        ResponseEntity<?> response = update(Map.of("budgetCredits", 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(workflowRepository).resetBudgetPeriodAt(eq(WORKFLOW_ID),
                eq(WorkflowBudgetPeriod.periodStart("monthly", Instant.now())));
    }

    @Test
    @DisplayName("changing the CADENCE starts a fresh period, in either direction")
    void cadenceChangeStartsAFreshPeriod() {
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");

        update(Map.of("budgetPeriodMode", "weekly"));

        verify(workflowRepository).resetBudgetPeriodAt(eq(WORKFLOW_ID),
                eq(WorkflowBudgetPeriod.periodStart("weekly", Instant.now())));
    }

    @Test
    @DisplayName("switching to the never-resets cadence clears the marker instead of binding a null")
    void cumulativeUsesItsOwnStatement() {
        // Two statements rather than one nullable bind: Postgres cannot infer a
        // null parameter's type and this repo has shipped that bug once already.
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");

        update(Map.of("budgetPeriodMode", "cumulative"));

        verify(workflowRepository).resetBudgetPeriodCumulative(WORKFLOW_ID);
        verify(workflowRepository, never()).resetBudgetPeriodAt(any(), any());
    }

    @Test
    @DisplayName("the response ships the date the allowance starts again, for the list page's chip")
    void responseShipsTheResetDate() {
        // The busiest surface reads this. Untested, the mapper can stop filling
        // it with the whole suite green, and every chip on /app/workflow
        // silently loses its only concrete promise.
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("weekly");

        ResponseEntity<?> response = update(Map.of("name", "Renamed"));

        var summary = (com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary) response.getBody();
        assertThat(summary.budgetPeriodResetsAt())
                .isEqualTo(WorkflowBudgetPeriod.nextPeriodStart("weekly", Instant.now()));
    }

    @Test
    @DisplayName("a cap that never resets ships NO date, which is how the popover knows to say so")
    void cumulativeShipsNoResetDate() {
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("cumulative");

        ResponseEntity<?> response = update(Map.of("name", "Renamed"));

        var summary = (com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary) response.getBody();
        assertThat(summary.budgetPeriodResetsAt()).isNull();
    }

    @Test
    @DisplayName("a RENAME never touches the counter, which is the whole reason the reset is conditional")
    void renameNeverResetsTheCounter() {
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");

        update(Map.of("name", "Renamed"));

        verify(workflowRepository, never()).resetBudgetPeriodAt(any(), any());
        verify(workflowRepository, never()).resetBudgetPeriodCumulative(any());
    }

    @Test
    @DisplayName("re-saving the SAME cadence is not a change, so the counter survives")
    void resavingTheSameCadenceIsNotAChange() {
        // The modal submits the cadence on every save. Treating each submission
        // as a change would hand every user a way to zero their own counter by
        // saving twice, and would make the cap unenforceable by accident.
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");

        update(Map.of("name", "Renamed", "budgetPeriodMode", "monthly"));

        verify(workflowRepository, never()).resetBudgetPeriodAt(any(), any());
        verify(workflowRepository, never()).resetBudgetPeriodCumulative(any());
    }

    @Test
    @DisplayName("raising an EXISTING cap keeps the spend: only the terms changed, not the period")
    void raisingAnExistingCapKeepsTheCounter() {
        // The user is not starting over, they are giving themselves more room.
        // Zeroing here would quietly forgive everything spent so far.
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");

        update(Map.of("budgetCredits", 50));

        verify(workflowRepository, never()).resetBudgetPeriodAt(any(), any());
        verify(workflowRepository, never()).resetBudgetPeriodCumulative(any());
    }

    @Test
    @DisplayName("CLEARING the cap resets nothing: there is no cap left for a period to belong to")
    void clearingTheCapResetsNothing() {
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");

        Map<String, Object> body = new HashMap<>();
        body.put("budgetCredits", null);
        update(body);

        assertThat(workflow.getBudgetCredits()).isNull();
        verify(workflowRepository, never()).resetBudgetPeriodAt(any(), any());
        verify(workflowRepository, never()).resetBudgetPeriodCumulative(any());
    }

    @Test
    @DisplayName("an unknown cadence is refused with 400 and changes nothing at all")
    void unknownCadenceIsRefused() {
        // Normalising a typo to "never resets" would turn a monthly allowance
        // into a lifetime cap, and the workflow would stop for good.
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");

        ResponseEntity<?> response = update(Map.of("budgetPeriodMode", "quarterly"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(workflow.getBudgetPeriodMode()).isEqualTo("monthly");
        verify(workflowRepository, never()).resetBudgetPeriodAt(any(), any());
    }

    // ─── What the PUT hands back. The period columns are updatable=false, so
    //     the managed entity still holds the PRE-reset figure after the native
    //     reset: a response built from it tells the user, in the same breath as
    //     accepting their new cap, that they are already over it. ───

    @Test
    @DisplayName("the response reports the RESET spend, not the figure the entity still holds")
    void responseReflectsTheReset() {
        ReflectionTestUtils.setField(workflow, "budgetPeriodSpent", new BigDecimal("50"));
        ReflectionTestUtils.setField(workflow, "budgetPeriodStartedAt",
                WorkflowBudgetPeriod.periodStart("monthly", Instant.now()));
        workflow.setBudgetPeriodMode("monthly");

        ResponseEntity<?> response = update(Map.of("budgetCredits", 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var summary = (com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary) response.getBody();
        assertThat(summary).isNotNull();
        assertThat(summary.budgetPeriodSpent())
                .as("50 was spent before the cap existed and has just been cleared")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an ordinary save still reports the real spend: the override is only for a reset")
    void responseKeepsTheSpendWhenNothingWasReset() {
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");
        ReflectionTestUtils.setField(workflow, "budgetPeriodSpent", new BigDecimal("4"));
        ReflectionTestUtils.setField(workflow, "budgetPeriodStartedAt",
                WorkflowBudgetPeriod.periodStart("monthly", Instant.now()));

        ResponseEntity<?> response = update(Map.of("name", "Renamed"));

        var summary = (com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary) response.getBody();
        assertThat(summary.budgetPeriodSpent()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a failed reset leaves the cap UNSET, so the retry can still repair it")
    void failedResetLeavesTheCapUnapplied() {
        // Order is the recovery story. Saving the cap first and resetting second
        // meant a failure between them persisted the cap while the pre-cap spend
        // still counted - and the retry could not fix it, because the cap now
        // existed, so "the cap just appeared" was false and the reset never ran
        // again. The user was left blocked by spend from before their own cap.
        workflow.setBudgetPeriodMode("monthly");
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(workflowRepository).resetBudgetPeriodAt(any(), any());

        assertThatThrownBy(() -> update(Map.of("budgetCredits", 10)))
                .hasMessageContaining("db down");

        assertThat(workflow.getBudgetCredits())
                .as("nothing was persisted, so the same request will do the right thing again")
                .isNull();
        verify(workflowRepository, never()).save(any());
    }
}
