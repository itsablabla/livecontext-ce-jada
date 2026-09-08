package com.apimarketplace.orchestrator.services.streaming.events;

import java.math.BigDecimal;

/**
 * Emitted when a trigger fire is refused because the run's accumulated cost has
 * reached the workflow budget: the in-flight epoch (if any) finishes, but no new
 * epoch starts. The run-mode UI surfaces this as an explanatory toast.
 *
 * <p>Figures in credits (1 credit = $0.001); the frontend renders dollars in CE
 * and raw credits in cloud.
 *
 * <p>{@code periodMode} is not decoration: the cap is an allowance PER PERIOD
 * that resets on its own, so a toast that omits it reads as a permanent stop
 * and sends the reader looking for a setting to undo. It is the one message a
 * blocked user actually sees.
 */
public record RunBudgetBlockedEvent(
    String runId,
    BigDecimal spentCredits,
    BigDecimal budgetCredits,
    String periodMode,
    long timestamp
) implements WorkflowEvent {

    public RunBudgetBlockedEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
    }
}
