package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * /state ships the epochs that are still open, the same union the WS snapshot publishes.
 *
 * <p>It is what a client needs to answer "is another fire of this run executing right now",
 * and that question decides whether a restart targeting an OLDER epoch can be offered at all:
 * {@code rerunFromStep} refuses to reopen one while a sibling epoch of the same DAG is still
 * running. Published only on the snapshot channel, the list is empty until the first batch
 * arrives, so a canvas that has only done the REST load reads "nothing is executing" and
 * offers a restart the backend answers with a 409.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - /state ships activeEpochs")
class WorkflowRunControllerActiveEpochsTest {

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private WorkflowResumeService resumeService;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private WorkflowEpochService workflowEpochService;

    @InjectMocks
    private WorkflowRunController controller;

    private static final String RUN_ID = "run-active-epochs";
    private static final String TENANT_ID = "tenant-A";

    @BeforeEach
    void wireRunAndState() {
        lenient().when(workflowEpochService.listEpochTimestamps(RUN_ID)).thenReturn(List.of());
        lenient().when(resumeService.reconstructStateForApi(RUN_ID)).thenReturn(new WorkflowRunState(
                RUN_ID, "wf-1", RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                Instant.now(), null, Map.of(), List.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Map.of(), List.of()));

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        run.setMetadata(new HashMap<>());
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    private void snapshotWith(StateSnapshot snapshot) {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
    }

    private ResponseEntity<?> callState() {
        return controller.getRunState(RUN_ID, false, TENANT_ID, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Object body(ResponseEntity<?> response, String key) {
        return ((Map<String, Object>) response.getBody()).get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> activeEpochs(ResponseEntity<?> response) {
        return (List<Integer>) body(response, "activeEpochs");
    }

    @Test
    @DisplayName("An open epoch is reported, so a client that only did the REST load knows work is in flight")
    void openEpochIsShipped() {
        // advanceEpoch opens epoch 1 and leaves it active.
        snapshotWith(StateSnapshot.empty().withDagState("trigger:start", DagState.initial().advanceEpoch(1)));

        ResponseEntity<?> response = callState();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activeEpochs(response)).containsExactly(1);
    }

    @Test
    @DisplayName("A run with no open epoch reports an EMPTY list, not a missing field")
    void quiescedRunShipsAnEmptyList() {
        // The distinction the reader depends on: "nothing is executing" has to be a stated
        // fact, or it cannot be told apart from "this payload does not carry the field", which
        // is what a client must NOT overwrite its snapshot-fed list with.
        snapshotWith(StateSnapshot.empty().withDagState("trigger:start", DagState.initial()));

        assertThat(activeEpochs(callState())).isEmpty();
    }

    @Test
    @DisplayName("An epoch opening or closing changes the ETag, so a cached body cannot outlive it")
    void activeEpochsParticipateInTheEtag() {
        // seq does not stand in for this. A body cached while epoch 1 was open keeps answering
        // "epoch 1 is executing" after it closes, and the client reads that to decide whether a
        // restart targeting an older epoch can be offered at all. Left out of the hash, the
        // response 304s and the decision is made on the previous set.
        snapshotWith(StateSnapshot.empty().withDagState("trigger:start", DagState.initial().advanceEpoch(1)));
        String whileOpen = callState().getHeaders().getETag();

        // Same epoch number, same seq, same status - only the epoch closed.
        snapshotWith(StateSnapshot.empty().withDagState("trigger:start",
                DagState.initial().advanceEpoch(1).closeAllActiveEpochs()));
        String afterClose = callState().getHeaders().getETag();

        assertThat(whileOpen).isNotNull();
        assertThat(afterClose).isNotEqualTo(whileOpen);
    }

    /**
     * A DAG holding MANY open epochs, out of natural order.
     *
     * <p>Element count is what makes the ordering assertions able to fail: {@code Set.copyOf}
     * only randomizes iteration for a multi-element set, and a two-element set of small
     * integers iterates in order anyway. With ten, an insertion-ordered or hash-ordered
     * collection shows through.
     */
    private static DagState dagWithManyOpenEpochs() {
        DagState dag = DagState.initial();
        for (int epoch : new int[] { 7, 3, 10, 1, 9, 4, 8, 2, 6, 5 }) {
            dag = dag.advanceEpoch(epoch);
        }
        return dag;
    }

    @Test
    @DisplayName("The ETag is STABLE while nothing moves, or every poll re-downloads the body")
    void etagIsStableWhenNothingChanges() {
        // The other ETag test proves the hash MOVES when an epoch closes. On its own that is
        // satisfied by a hash that changes every call - which would defeat the 304 entirely.
        // Both halves are needed, and the set's iteration order is exactly what could break
        // this one, so the fixture carries enough epochs for that order to vary.
        snapshotWith(StateSnapshot.empty().withDagState("trigger:a", dagWithManyOpenEpochs()));

        assertThat(callState().getHeaders().getETag())
                .isEqualTo(callState().getHeaders().getETag());
    }

    @Test
    @DisplayName("Many open epochs are reported in a stable ORDER, not the set's own")
    void manyEpochsAreOrdered() {
        // containsExactly, in order: the response array is a TreeSet precisely because
        // DagState.getActiveEpochs() returns a Set.copyOf whose iteration order is randomized
        // per JVM. An order-insensitive assertion is the one form that cannot catch that, and
        // a two-element fixture is the one size that cannot exercise it.
        snapshotWith(StateSnapshot.empty().withDagState("trigger:a", dagWithManyOpenEpochs()));

        assertThat(activeEpochs(callState())).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    @DisplayName("Epochs open on DIFFERENT triggers are unioned, like the snapshot does")
    void everyDagContributes() {
        // A run can hold one DAG per trigger, each with its own epochs. The client guard is a
        // superset of the backend's per-DAG refusal, which is the conservative direction.
        snapshotWith(StateSnapshot.empty()
                .withDagState("trigger:a", DagState.initial().advanceEpoch(1))
                .withDagState("trigger:b", DagState.initial().advanceEpoch(2)));

        assertThat(activeEpochs(callState())).containsExactly(1, 2);
    }
}
