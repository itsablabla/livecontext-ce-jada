package com.apimarketplace.orchestrator.services.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Raw projection behind {@code findBudgetStateByRunIdPublic}, before the run's
 * metadata is reduced to the one flag the rule cares about.
 *
 * <p>It carries the whole metadata map rather than a boolean because the
 * {@code __editorRun__} flag lives inside JSONB, and reading it in SQL is not
 * portable: the Postgres {@code ->>} operator is a syntax error on the H2 the
 * repository tests run on, which would have left the single most important read
 * in this feature covered by nothing but mocks. Selecting the mapped attribute
 * and reducing it in Java keeps the query to one round-trip AND keeps it
 * executable by a real test.
 */
public record WorkflowBudgetStateRow(
        UUID workflowId,
        boolean productionRun,
        Map<String, Object> metadata,
        BigDecimal budgetCredits,
        String periodMode,
        BigDecimal periodSpent,
        Instant periodStartedAt) {

    private static final String META_EDITOR_RUN = "__editorRun__";

    /** Typed state, with the metadata reduced to the builder-test-fire flag. */
    public WorkflowBudgetState toState() {
        return new WorkflowBudgetState(
                workflowId, productionRun, isEditorRun(),
                budgetCredits, periodMode, periodSpent, periodStartedAt);
    }

    private boolean isEditorRun() {
        if (metadata == null) {
            return false;
        }
        Object flag = metadata.get(META_EDITOR_RUN);
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }
}
