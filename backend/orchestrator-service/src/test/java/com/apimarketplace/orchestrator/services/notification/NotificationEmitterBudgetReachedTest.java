package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowBudgetReachedEvent;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the BUDGET_REACHED listener on {@link NotificationEmitter}.
 *
 * <p>This notification is the only thing that tells an owner their production
 * workflow has stopped spending. The toast that already existed reaches whoever
 * has the run open, which is nobody when a nightly automation hits its cap, so
 * every branch below is about the row actually being written and being written
 * exactly once per period.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationEmitter - BUDGET_REACHED")
class NotificationEmitterBudgetReachedTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowRedisPublisher redisPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;

    private MeterRegistry meterRegistry;
    private NotificationEmitter emitter;

    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String RUN_PUBLIC = "run_pub_budget";
    private static final String TENANT_ID = "tenant-1";
    private static final Instant PERIOD_START =
            ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        emitter = new NotificationEmitter(workflowRepository, workflowRunRepository,
                redisPublisher, meterRegistry);
        Field emField = NotificationEmitter.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(emitter, entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
    }

    private WorkflowRunEntity run(String tenantId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        try {
            Field idField = WorkflowRunEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(run, RUN_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        run.setRunIdPublic(RUN_PUBLIC);
        run.setTenantId(tenantId);
        run.setOrganizationId("org-1");
        run.setPlanVersion(4);
        return run;
    }

    private WorkflowEntity workflow() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setName("Nightly digest");
        return wf;
    }

    private WorkflowBudgetReachedEvent event(String mode, Instant periodStart) {
        return eventWithCap(mode, periodStart, new BigDecimal("10"));
    }

    private WorkflowBudgetReachedEvent eventWithCap(String mode, Instant periodStart, BigDecimal cap) {
        return new WorkflowBudgetReachedEvent(RUN_PUBLIC, WORKFLOW_ID,
                cap, cap, mode, periodStart, PERIOD_START);
    }

    private void stubRunAndWorkflow() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC)).thenReturn(Optional.of(run(TENANT_ID)));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));
    }

    @Test
    @DisplayName("writes one WARNING row and pushes it, keyed on the workflow and the period")
    void writesTheRowAndPushes() {
        stubRunAndWorkflow();
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onBudgetReached(event("monthly", PERIOD_START));

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery, org.mockito.Mockito.atLeastOnce()).setParameter(anyInt(), values.capture());
        List<Object> all = values.getAllValues();
        assertThat(all).contains(TENANT_ID, "BUDGET_REACHED", "warning");
        // Reaching a cap is not a breakage: the automation did exactly what it
        // was configured to do, so it must not be raised as an error. The
        // severity IS one of these bound parameters (the "warning" asserted
        // just above), so this fails if anybody promotes it.
        assertThat(all).doesNotContain("error");
        // The dedup key names the period AND the cap, so next month notifies
        // again, and so does a NEW cap reached inside the same month.
        assertThat(all).contains(WORKFLOW_ID + ":" + PERIOD_START + ":10");

        verify(redisPublisher).publishNotification(eq(TENANT_ID), eq("notification.created"), any());
        verify(redisPublisher).publishOrgNotification(eq("org-1"), eq("notification.created"), any());
    }

    @Test
    @DisplayName("the payload carries `status`, which the table's CHECK constraint requires")
    void payloadSatisfiesTheCheckConstraint() {
        // orchestrator.notifications carries chk_notif_payload_v1
        // CHECK (payload ? 'status') (V172, relaxed to this single key by V174).
        // Omitting it makes the INSERT raise 23514, which this listener's own
        // catch swallows into a WARN: the row would never exist, no caller would
        // ever learn that, and the whole durable-notification path would ship
        // green and dead. Asserting the parameter VALUES alone cannot see that,
        // because the EntityManager is mocked - so assert the payload shape the
        // database actually demands.
        stubRunAndWorkflow();
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onBudgetReached(event("monthly", PERIOD_START));

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery, org.mockito.Mockito.atLeastOnce()).setParameter(anyInt(), values.capture());
        String payloadJson = values.getAllValues().stream()
                .filter(v -> v instanceof String s && s.startsWith("{"))
                .map(String.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JSON payload was bound to the insert"));
        assertThat(payloadJson).contains("\"status\"");
        assertThat(payloadJson).contains("\"spentCredits\"").contains("\"capCredits\"");
    }

    @Test
    @DisplayName("a cumulative cap has no period, so its key says so - but a NEW cap still notifies")
    void cumulativeKeyNamesTheCapNotJustThePeriod() {
        // A lifetime cap never rolls over, so the period half of the key never
        // changes. If the key stopped there, the workflow would announce itself
        // once in its entire life: raise the cap, hit the new one, and nobody is
        // told the automation stopped again.
        stubRunAndWorkflow();
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onBudgetReached(event("cumulative", null));
        emitter.onBudgetReached(eventWithCap("cumulative", null, new BigDecimal("500")));

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery, org.mockito.Mockito.atLeastOnce()).setParameter(anyInt(), values.capture());
        assertThat(values.getAllValues()).contains(WORKFLOW_ID + ":cumulative:10");
        assertThat(values.getAllValues()).contains(WORKFLOW_ID + ":cumulative:500");
    }

    @Test
    @DisplayName("does not push when the insert was a duplicate for this period")
    void noPushOnConflict() {
        // ON CONFLICT DO NOTHING returns no row. Pushing anyway would ring the
        // bell on every refused fire while showing a single notification.
        stubRunAndWorkflow();
        when(nativeQuery.getResultList()).thenReturn(List.of());

        emitter.onBudgetReached(event("monthly", PERIOD_START));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("ignores an event whose run no longer exists")
    void ignoresMissingRun() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC)).thenReturn(Optional.empty());

        emitter.onBudgetReached(event("monthly", PERIOD_START));

        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("ignores an event whose run carries no tenant: the row would be unreadable")
    void ignoresTenantlessRun() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC)).thenReturn(Optional.of(run(null)));

        emitter.onBudgetReached(event("monthly", PERIOD_START));

        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("ignores an event whose workflow was deleted")
    void ignoresMissingWorkflow() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC)).thenReturn(Optional.of(run(TENANT_ID)));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        emitter.onBudgetReached(event("monthly", PERIOD_START));

        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("a DB failure is counted and swallowed, never propagated to the publisher")
    void dbFailureSwallowed() {
        stubRunAndWorkflow();
        when(nativeQuery.getResultList()).thenThrow(new DataAccessResourceFailureException("db down"));

        // must not throw: the spend it reports has already been committed
        emitter.onBudgetReached(event("monthly", PERIOD_START));

        assertThat(meterRegistry.find("notification.emitter.errors").counters()).isNotEmpty();
    }

    @Test
    @DisplayName("the listener runs even with no transaction bound, or the notification is dead code")
    void listenerFallsBackOutsideATransaction() throws Exception {
        // The trigger gate that refuses a fire publishes from
        // ReusableTriggerService.executeTriggerInternal, which is NOT
        // @Transactional. A plain AFTER_COMMIT listener silently DROPS an event
        // published with no transaction bound - no row, no log, and every unit
        // test still green because they call the listener directly. This guard
        // pins the one annotation attribute that keeps that path alive.
        Method listener = NotificationEmitter.class.getMethod("onBudgetReached", WorkflowBudgetReachedEvent.class);
        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution())
                .as("BUDGET_REACHED is published outside a transaction by the trigger gate")
                .isTrue();
    }

    @Test
    @DisplayName("raising the cap and hitting the new one in the SAME period notifies again")
    void aNewCapInTheSamePeriodIsANewNotification() {
        // Dedup is what stops one crossing being announced twice. It must not
        // also swallow a SECOND, genuine stop: a user who is blocked, raises the
        // cap, and is blocked again by the higher cap has hit a different limit
        // and has to be told. Without the cap in the key the row already exists
        // and ON CONFLICT DO NOTHING silently drops it. Under the never-resets
        // cadence the period never changes either, so the workflow would
        // announce itself once in its whole life and stop in silence after.
        stubRunAndWorkflow();
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onBudgetReached(event("monthly", PERIOD_START));
        emitter.onBudgetReached(eventWithCap("monthly", PERIOD_START, new java.math.BigDecimal("50")));

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery, org.mockito.Mockito.atLeastOnce()).setParameter(anyInt(), values.capture());
        List<Object> all = values.getAllValues();
        assertThat(all).contains(WORKFLOW_ID + ":" + PERIOD_START + ":10");
        assertThat(all).contains(WORKFLOW_ID + ":" + PERIOD_START + ":50");
    }

    @Test
    @DisplayName("the same cap written 10, 10.0 or 10.00 is ONE key, not three notifications")
    void capScaleDoesNotSplitTheDedupKey() {
        stubRunAndWorkflow();
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onBudgetReached(eventWithCap("monthly", PERIOD_START, new java.math.BigDecimal("10.00")));

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery, org.mockito.Mockito.atLeastOnce()).setParameter(anyInt(), values.capture());
        assertThat(values.getAllValues()).contains(WORKFLOW_ID + ":" + PERIOD_START + ":10");
    }
}
