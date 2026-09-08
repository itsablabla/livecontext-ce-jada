package com.apimarketplace.agent.memory;

import java.util.UUID;

/**
 * Resolves the agent a memory read or write is scoped to, from whatever the
 * caller happened to carry it in.
 *
 * <p>The three runtime paths that inject or serve memory each receive the agent
 * id in a different shape: a DTO field on the direct-API path, a JSON body value
 * on the internal bridge endpoint, and a credentials entry on the tool path. All
 * three want the same answer, and it is deliberately LENIENT: absent, blank or
 * unparseable all mean <em>no agent is bound</em>, which resolves memory to
 * workspace scope. That is the safe direction. A chat with no agent legitimately
 * has none, and refusing there would deny the workspace's own memories to an
 * ordinary conversation; widening in the other direction is impossible, because
 * a null never selects an agent's private entries.
 *
 * <p>Not to be confused with {@code MemoryController.resolveRequestedAgentScope},
 * which is STRICT on purpose: when a person explicitly asks that a memory be kept
 * private to one agent, swallowing a malformed id would store it for the whole
 * workspace and report success, silently widening an audience the field exists to
 * narrow.
 */
public final class MemoryAgentScope {

    private MemoryAgentScope() {
    }

    /** The bound agent, or null when there is none to bind. */
    public static UUID agentIdOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
