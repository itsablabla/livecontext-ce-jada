package com.apimarketplace.orchestrator.services.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a workflow's spending cap stops it from firing again.
 *
 * <p>Consumed by {@code NotificationEmitter.onBudgetReached} via
 * {@link org.springframework.transaction.event.TransactionalEventListener}
 * (AFTER_COMMIT) to write one {@code BUDGET_REACHED} notification row.
 *
 * <p>Why a durable notification and not just the existing toast: the toast is a
 * websocket event painted by whoever has that run open. The case this feature
 * exists for is an unattended workflow firing at night, so the only person who
 * would ever see the toast is asleep. Reaching a cap silently stops a
 * production automation, which is a deliberate outage: it has to be loud.
 *
 * <p>{@code sourceId} is {@code <workflowId>:<periodStart>:<cap>} so the unique
 * {@code (tenant_id, category, source_id)} index gives exactly one notification
 * per workflow, per period, per cap for free, across replicas, however many
 * fires the cap refuses. A cumulative cap has no period, so it uses the literal
 * {@code cumulative} for that part; the cap is what still lets it announce a
 * NEW limit rather than falling silent for the workflow's whole life. See
 * {@link #sourceId()} for the full reasoning.
 */
public record WorkflowBudgetReachedEvent(
        String runIdPublic,
        UUID workflowId,
        BigDecimal spentCredits,
        BigDecimal capCredits,
        String periodMode,
        Instant periodStart,
        Instant occurredAt) {

    /**
     * Dedup key: one notification per workflow, per budget period, per CAP.
     *
     * <p>The cap amount is part of the key, not decoration. Without it, a user
     * who is stopped, raises the cap, and is stopped again by the NEW cap in the
     * same period is never told the second time: the row already exists and
     * {@code ON CONFLICT DO NOTHING} swallows it. Under the never-resets mode
     * the period never changes either, so the workflow would announce itself
     * once in its entire life and stop in silence ever after. Keying on the cap
     * makes each distinct promise announce itself once, which is what the user
     * agreed to be told about.
     *
     * <p>The amount is normalised through {@code stripTrailingZeros} so the same
     * cap written 10, 10.0 or 10.00 is one key and not three notifications.
     */
    public String sourceId() {
        String period = periodStart != null ? periodStart.toString() : "cumulative";
        String cap = capCredits != null ? capCredits.stripTrailingZeros().toPlainString() : "none";
        return workflowId + ":" + period + ":" + cap;
    }
}
