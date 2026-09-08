package com.apimarketplace.agent.client.dto.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy that carries long-term memory into a request.
 *
 * <p>{@code withSystemPrompt} re-lists forty positional components. Every
 * injection test in this feature reads back {@code systemPrompt} and nothing
 * else, so a swapped pair anywhere in that list - {@code loopIdenticalStop} for
 * {@code loopConsecutiveStop}, {@code subAgentId} for {@code workflowRunId} -
 * would corrupt every execution the moment memory is enabled while the whole
 * suite stayed green. The compiler cannot help: the neighbours have the same
 * types.
 *
 * <p>The round trip below is the cheapest complete check. Give every component a
 * DISTINCT value, replace the prompt, put the original prompt back, and the
 * result must equal the original record. Any transposition survives one hop and
 * fails the second, because the two values differ.
 */
@DisplayName("AgentExecutionRequestDto.withSystemPrompt")
class AgentExecutionRequestDtoWithSystemPromptTest {

    /** Distinct values throughout: two components holding the same value would hide a swap between them. */
    private static AgentExecutionRequestDto distinctDto() {
        return new AgentExecutionRequestDto(
            "prompt-1", "system-2", "provider-3", "model-4", 0.5, 61,
            List.of(Map.<String, Object>of("tool", "7")),
            true, 8, 9, 10, List.of(Map.<String, Object>of("history", "11")),
            "tenant-12", "run-13", "node-14",
            Map.<String, Object>of("var", "15"), Map.<String, Object>of("cred", "16"),
            17.0, "stream-18", 19, 20, "conversation-21", "streaming-22", "parent-23",
            "subagent-24", "avatar-25", "subagentid-26", "workflowrun-27",
            List.of(Map.<String, Object>of("attachment", "28")), UUID.randomUUID().toString(),
            29.0, List.of(Map.<String, Object>of("rate", "30")), 31.0, 32, 33,
            "execution-34", "source-35", "effort-36", List.of("module-37"));
    }

    @Test
    @DisplayName("changes the prompt and nothing else, which a swapped pair of the other 39 fields would break")
    void aRoundTripReturnsTheOriginal() {
        AgentExecutionRequestDto original = distinctDto();

        AgentExecutionRequestDto roundTripped = original
            .withSystemPrompt("a prompt with the memory block appended")
            .withSystemPrompt(original.systemPrompt());

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("actually replaces the prompt, so the round trip above is not passing on a no-op")
    void theReplacementReallyHappens() {
        AgentExecutionRequestDto original = distinctDto();

        assertThat(original.withSystemPrompt("enriched").systemPrompt()).isEqualTo("enriched");
    }

    @Test
    @DisplayName("returns the same instance when the prompt is unchanged, so an empty block allocates nothing")
    void anUnchangedPromptIsANoOp() {
        AgentExecutionRequestDto original = distinctDto();

        // The common case by far: most workspaces have no memory, and appendTo hands
        // back the prompt it was given.
        assertThat(original.withSystemPrompt(original.systemPrompt())).isSameAs(original);
    }

    @Test
    @DisplayName("copies every component the record declares, so a field added later cannot be quietly dropped")
    void everyComponentSurvivesTheCopy() throws Exception {
        AgentExecutionRequestDto original = distinctDto();
        AgentExecutionRequestDto copy = original.withSystemPrompt("enriched");

        // Reflective, so a component added to the record after this was written is
        // covered without anyone remembering to extend the test. A new field left out
        // of withSystemPrompt arrives as null here.
        for (RecordComponent component : AgentExecutionRequestDto.class.getRecordComponents()) {
            if (component.getName().equals("systemPrompt")) {
                continue;
            }
            Object before = component.getAccessor().invoke(original);
            Object after = component.getAccessor().invoke(copy);
            assertThat(after)
                .as("component '%s' was lost or changed by withSystemPrompt", component.getName())
                .isEqualTo(before);
        }
    }
}
