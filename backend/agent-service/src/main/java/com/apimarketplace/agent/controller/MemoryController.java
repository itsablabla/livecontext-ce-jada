package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentMemoryEntity;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemorySource;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemoryType;
import com.apimarketplace.agent.memory.MemoryLimitsConfig;
import com.apimarketplace.agent.memory.MemoryService;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The human side of long-term memory: the Memory tab reads and edits rows here.
 *
 * <p>The whole point of the feature is that memory is inspectable and correctable
 * by a person, not a black box the agents fill in. So everything the agent tool
 * can do, this can do, and it can do one thing more: it writes rows tagged
 * {@code source=USER}, which is what lets a reader tell the facts they asserted
 * from the ones an agent decided to keep.
 *
 * <p>Scope handling is the same on both surfaces and comes from the gateway, not
 * from the caller: the active workspace arrives as {@code X-Organization-ID} and
 * an out-of-scope row is a <b>404, not a 403</b>, so the existence of another
 * workspace's memory is never confirmed to someone outside it.
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;
    private final TenantResolver tenantResolver;
    private final RequestParameterExtractor extractor;
    private final AgentRepository agentRepository;
    private final MemoryLimitsConfig limits;

    public MemoryController(MemoryService memoryService,
                            TenantResolver tenantResolver,
                            RequestParameterExtractor extractor,
                            AgentRepository agentRepository,
                            MemoryLimitsConfig limits) {
        this.memoryService = memoryService;
        this.tenantResolver = tenantResolver;
        this.extractor = extractor;
        this.agentRepository = agentRepository;
        this.limits = limits;
    }

    /**
     * Turn "no workspace on this request" into a 400 rather than a 500.
     *
     * <p>Every service call resolves the workspace and refuses without one. The
     * WRITE endpoints already catch that; the reads and the delete did not, so the
     * same condition surfaced as a generic 500 from the global handler on half the
     * surface and as an actionable 400 on the other half. The tool surface reports
     * it explicitly too.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(MemoryService.MemoryValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MemoryService.MemoryValidationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * Refuse a WRITE when long-term memory is switched off for this installation.
     *
     * <p>Reads and deletes stay open on purpose. Turning the feature off stops new
     * facts being stored and stops the block being injected; it is not a reason to
     * hide rows that already exist, and an operator who has just switched it off is
     * exactly the person who may want to delete what accumulated. Refusing writes
     * only is what makes the flag mean the same thing on both surfaces: the tool
     * already answers that nothing is stored and nothing is recalled, while this
     * surface used to keep creating rows that no agent would ever see.
     */
    private Optional<ResponseEntity<Object>> refuseIfDisabled() {
        if (limits.isEnabled()) {
            return Optional.empty();
        }
        // 503, not 409: nothing about the request conflicts with anything: the
        // capability is simply not on in this installation.
        return Optional.of(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "error", "Long-term memory is switched off in this installation, so nothing new can be "
                + "stored. Existing entries stay readable and can still be deleted.")));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);

        // The WHOLE workspace, agent-private entries included and switched-off ones
        // included. A person auditing memory has to see the rows they cannot
        // otherwise reach, or the ones they cannot see are exactly the ones they
        // cannot fix, and this screen is the only place a deactivated entry can be
        // switched back on. The agent's narrower "visible to me" view belongs to the
        // tool, which is where it is used.
        List<AgentMemoryEntity> entries = memoryService.listWorkspace(orgId);

        return ResponseEntity.ok(entries.stream().map(MemoryController::toRowDto).toList());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> search(HttpServletRequest httpRequest,
                                                            @RequestParam String query,
                                                            @RequestParam(required = false) Integer limit) {
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        // Same rows the tab lists: a person auditing memory must be able to find an
        // agent-private entry, not just see it. The agent tool scopes its own search.
        List<AgentMemoryEntity> hits = memoryService.searchWorkspace(orgId, query, limit != null ? limit : 25);
        return ResponseEntity.ok(hits.stream().map(MemoryController::toRowDto).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest httpRequest, @PathVariable UUID id) {
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);

        // No recall bump on this path: a person opening a row in the UI is not the
        // agent reaching for the fact, and counting it would corrupt the only
        // signal a human has for deciding what is actually being used.
        Optional<AgentMemoryEntity> found = memoryService.findInScope(id, orgId);
        return found.map(entity -> ResponseEntity.ok(toDto(entity)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest httpRequest, @RequestBody Map<String, Object> body) {
        Optional<ResponseEntity<Object>> disabled = refuseIfDisabled();
        if (disabled.isPresent()) return disabled.get();
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);

        UUID scopeAgentId;
        try {
            scopeAgentId = resolveRequestedAgentScope(extractor.getText(body, "agentId"), orgId);
        } catch (MemoryService.MemoryValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        try {
            AgentMemoryEntity saved = memoryService.create(new MemoryService.SaveRequest(
                tenantId, orgId, scopeAgentId,
                extractor.getText(body, "slug"),
                extractor.getText(body, "title"),
                extractor.getText(body, "summary"),
                extractor.getText(body, "content"),
                parseType(extractor.getText(body, "type")),
                readTags(body),
                extractor.getBoolean(body, "pinned"),
                MemorySource.USER,
                null
            ), orgRole);
            // Always 201, because this endpoint now only ever creates. It used to be
            // an upsert, which is right for an agent correcting a fact it recorded and
            // wrong for a person pressing "new": the form posts every field, so an
            // accidental title collision replaced the existing body with an empty one.
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
        } catch (MemoryService.MemoryConflictException e) {
            // 409, and the message NAMES the entry in the way (its title and its
            // handle), so the person is not left guessing which of their memories
            // collided. It used to carry the id and slug as separate fields too; no
            // caller ever read them, and an unread field is a contract to maintain
            // for nothing.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (MemoryService.MemoryWriteForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (MemoryService.MemoryValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest httpRequest,
                                    @PathVariable UUID id,
                                    @RequestBody Map<String, Object> body) {
        Optional<ResponseEntity<Object>> disabled = refuseIfDisabled();
        if (disabled.isPresent()) return disabled.get();
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);

        try {
            AgentMemoryEntity saved = memoryService.update(id, orgId, orgRole,
                extractor.getText(body, "title"),
                extractor.getText(body, "summary"),
                extractor.getText(body, "content"),
                parseType(extractor.getText(body, "type")),
                readTags(body),
                extractor.getBoolean(body, "pinned"),
                extractor.getBoolean(body, "isActive"),
                // A human edited the text here, so the row is now theirs.
                MemorySource.USER);
            return ResponseEntity.ok(toDto(saved));
        } catch (MemoryService.MemoryWriteForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (MemoryService.MemoryNotFoundException e) {
            // A row outside the caller's workspace is indistinguishable from one that
            // does not exist, on purpose: 404 rather than 403 so the boundary does not
            // confirm what lives behind it.
            return ResponseEntity.notFound().build();
        } catch (MemoryService.MemoryValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest httpRequest, @PathVariable UUID id) {
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);

        try {
            memoryService.delete(id, orgId, orgRole);
            return ResponseEntity.noContent().build();
        } catch (MemoryService.MemoryWriteForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (MemoryService.MemoryNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (MemoryService.MemoryValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Mapping ====================

    /**
     * A row for a LIST, which is every field except the body.
     *
     * <p>The body is the only unbounded field on the entity (8000 characters by
     * default) and nothing on a list screen renders it. Sending it with every row
     * made the cost of opening the Memory tab scale with the total size of the
     * workspace's memory rather than with the number of entries: a workspace that
     * has been accumulating facts for a year would ship megabytes to draw a list of
     * titles, and again on every search. The editor fetches the one entry it is
     * about to open, through {@code GET /api/memories/{id}}.
     */
    private static Map<String, Object> toRowDto(AgentMemoryEntity entity) {
        Map<String, Object> dto = toDto(entity);
        dto.remove("content");
        return dto;
    }

    /** The whole entry, body included. For the single-entry reads and the write responses. */
    private static Map<String, Object> toDto(AgentMemoryEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId().toString());
        dto.put("slug", entity.getSlug());
        dto.put("title", entity.getTitle());
        dto.put("summary", entity.getSummary());
        dto.put("content", entity.getContent());
        dto.put("type", entity.getType().name().toLowerCase(Locale.ROOT));
        dto.put("tags", entity.getTags());
        dto.put("pinned", entity.getPinned());
        dto.put("source", entity.getSource().name().toLowerCase(Locale.ROOT));
        dto.put("agentId", entity.getAgentId() != null ? entity.getAgentId().toString() : null);
        dto.put("scope", entity.getAgentId() == null ? "workspace" : "agent");
        dto.put("isActive", entity.getIsActive());
        dto.put("recallCount", entity.getRecallCount());
        dto.put("lastRecalledAt", entity.getLastRecalledAt() != null ? entity.getLastRecalledAt().toString() : null);
        dto.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        dto.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return dto;
    }

    /**
     * Resolve the requested agent scope, refusing anything the caller has no business
     * naming.
     *
     * <p>Absent or blank means workspace scope, which is the normal case. Anything
     * else must be an agent in the CALLER'S workspace, and both failure modes are
     * refused rather than absorbed:
     * <ul>
     *   <li>An unparseable id would otherwise become {@code null} and store, as
     *       readable by the whole workspace, a memory the caller asked to keep
     *       private to one agent. Silent widening of an audience is the one failure
     *       this column exists to prevent.</li>
     *   <li>An agent id belonging to another workspace would otherwise be accepted
     *       by the foreign key and rejected only when it does not exist at all,
     *       turning this endpoint into an oracle for "is this UUID an agent
     *       somewhere on the platform".</li>
     * </ul>
     */
    private UUID resolveRequestedAgentScope(String raw, String orgId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        UUID agentId;
        try {
            agentId = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new MemoryService.MemoryValidationException(
                "'" + raw + "' is not a valid agent id. Omit agentId to store the memory for the whole workspace.");
        }
        if (orgId == null || orgId.isBlank() || !agentRepository.existsByIdAndOrganizationIdStrict(agentId, orgId)) {
            throw new MemoryService.MemoryValidationException(
                "No such agent in this workspace. Omit agentId to store the memory for the whole workspace.");
        }
        return agentId;
    }


    /**
     * Parse the {@code type} field, refusing a value that is not one of the four.
     *
     * <p>Absent stays absent (the service applies its own default). An unrecognized
     * value used to become {@code null} here and then silently default to PROJECT,
     * so a typo filed the entry under the wrong type with a 201 and no hint, while
     * the agent tool answered 400 for the same input. Same input, same answer.
     */
    private static MemoryType parseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return MemoryService.parseType(raw).orElseThrow(() ->
            new MemoryService.MemoryValidationException(
                "Unknown memory type '" + raw.trim() + "'. Use one of: "
                    + MemoryService.typeNames() + "."));
    }

    @SuppressWarnings("unchecked")
    private static List<String> readTags(Map<String, Object> body) {
        Object raw = body.get("tags");
        if (raw instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return null;
    }
}
