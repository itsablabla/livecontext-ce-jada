package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.memory.MemoryAgentScope;
import com.apimarketplace.agent.memory.MemoryPromptSection;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Long-term memory for a caller that assembles a system prompt in ANOTHER service.
 *
 * <p>Most executions are dispatched to agent-service and get the block appended
 * there, at the single point above the bridge/direct split. Conversation-service
 * is the exception: when the chat model is a CLI provider it posts to the bridge
 * itself and never calls agent-service at all, so nothing in agent-service is in
 * a position to append anything. That path is not a corner case, it is the
 * default for every claude-code / codex / gemini-cli chat.
 *
 * <p>The endpoint takes the prompt and returns it with the block appended, rather
 * than returning the block for the caller to join on. That keeps ONE place that
 * decides the separator, the ordering and what happens when the block is empty.
 * Handing back a fragment would fork that decision into every service that
 * assembles a prompt, which is exactly the drift the skills tree already has.
 *
 * <p>Internal network only, no gateway route: the workspace arrives as a header
 * from the calling service, the same way every other {@code /api/internal/*}
 * endpoint resolves it.
 */
@RestController
@RequestMapping("/api/internal/memories")
public class InternalMemoryController {

    private static final Logger log = LoggerFactory.getLogger(InternalMemoryController.class);

    private final MemoryPromptSection memoryPromptSection;
    private final TenantResolver tenantResolver;

    public InternalMemoryController(MemoryPromptSection memoryPromptSection,
                                    TenantResolver tenantResolver) {
        this.memoryPromptSection = memoryPromptSection;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Append the workspace's memory block to a system prompt.
     *
     * <p>Never fails the caller: an execution without memory is degraded, an
     * execution that 500s because a memory read timed out is broken. On any
     * problem the prompt comes back exactly as it went in.
     */
    @PostMapping("/append-block")
    public ResponseEntity<Map<String, String>> appendBlock(HttpServletRequest httpRequest,
                                                           @RequestBody Map<String, Object> body) {
        String systemPrompt = body.get("systemPrompt") instanceof String s ? s : "";
        try {
            String orgId = tenantResolver.resolveOrgId(httpRequest);
            UUID agentId = MemoryAgentScope.agentIdOrNull(body.get("agentId"));
            return ResponseEntity.ok(Map.of(
                "systemPrompt", memoryPromptSection.appendTo(systemPrompt, orgId, agentId)));
        } catch (Exception e) {
            log.warn("[MEMORY] Could not append the memory block for an external prompt: {}", e.toString());
            return ResponseEntity.ok(Map.of("systemPrompt", systemPrompt));
        }
    }

}
