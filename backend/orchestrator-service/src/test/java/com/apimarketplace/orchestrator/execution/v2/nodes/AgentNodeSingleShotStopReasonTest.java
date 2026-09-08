package com.apimarketplace.orchestrator.execution.v2.nodes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression: classify / guardrail observability rows carried no stop_reason
 * (they are single-shot calls with no loop guard to name one), which left 75% of
 * all agent runs without a terminal category in product analytics and the
 * agent-health views. The minimal builder now derives it from the outcome.
 */
class AgentNodeSingleShotStopReasonTest {

    @Test
    @DisplayName("a COMPLETED single-shot execution stops with COMPLETED")
    void completed() {
        assertEquals("COMPLETED", AgentNode.deriveSingleShotStopReason("COMPLETED"));
    }

    @Test
    @DisplayName("anything else (FAILED, unknown, null) stops with ERROR")
    void failed() {
        assertEquals("ERROR", AgentNode.deriveSingleShotStopReason("FAILED"));
        assertEquals("ERROR", AgentNode.deriveSingleShotStopReason("weird"));
        assertEquals("ERROR", AgentNode.deriveSingleShotStopReason(null));
    }
}
