package com.apimarketplace.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one lenient agent-id resolver the three runtime paths share.
 *
 * <p>It replaced four private copies that had drifted in their input types (one
 * took a String, one an Object, one only accepted a String instance and returned
 * null for a UUID) while all four documented the same intent. The direction of
 * the leniency is what these tests pin: unusable input resolves to WORKSPACE
 * scope, never to some agent's private entries.
 */
@DisplayName("MemoryAgentScope")
class MemoryAgentScopeTest {

    @Nested
    @DisplayName("a bound agent")
    class Bound {

        @Test
        @DisplayName("parses the id a caller sends as a string, which is how every transport carries it")
        void parsesAString() {
            UUID id = UUID.randomUUID();

            assertThat(MemoryAgentScope.agentIdOrNull(id.toString())).isEqualTo(id);
        }

        @Test
        @DisplayName("accepts a UUID that arrived already parsed, rather than answering 'no agent'")
        void acceptsAnAlreadyParsedUuid() {
            UUID id = UUID.randomUUID();

            // The tool path reads this out of a credentials map typed Object, so the
            // value can legitimately arrive as a UUID. One of the four copies this
            // replaced tested `instanceof String` first and answered null here, which
            // silently downgraded that caller to workspace scope.
            assertThat(MemoryAgentScope.agentIdOrNull(id)).isEqualTo(id);
        }

        @Test
        @DisplayName("tolerates surrounding whitespace instead of losing the agent to a stray space")
        void trims() {
            UUID id = UUID.randomUUID();

            assertThat(MemoryAgentScope.agentIdOrNull("  " + id + "  ")).isEqualTo(id);
        }
    }

    @Nested
    @DisplayName("no agent to bind")
    class Unbound {

        @Test
        @DisplayName("answers null for absent, blank and unparseable, which resolves memory to the workspace")
        void everyUnusableInputIsWorkspaceScope() {
            // Null is the SAFE answer, and that is why this is lenient rather than
            // strict: a plain conversation legitimately has no agent, and refusing
            // here would deny it the workspace's own memories. It cannot widen an
            // audience, because null never selects an agent's private entries.
            assertThat(MemoryAgentScope.agentIdOrNull(null)).isNull();
            assertThat(MemoryAgentScope.agentIdOrNull("")).isNull();
            assertThat(MemoryAgentScope.agentIdOrNull("   ")).isNull();
            assertThat(MemoryAgentScope.agentIdOrNull("not-a-uuid")).isNull();
            assertThat(MemoryAgentScope.agentIdOrNull(42)).isNull();
        }

        @Test
        @DisplayName("does not throw on a truncated id, so a malformed payload cannot fail a run")
        void aTruncatedIdIsNotAnError() {
            // This runs on the prompt-assembly path, before anything is dispatched.
            // An exception here would fail the whole execution over an enrichment.
            assertThat(MemoryAgentScope.agentIdOrNull("f47ac10b-58cc-4372")).isNull();
        }
    }
}
