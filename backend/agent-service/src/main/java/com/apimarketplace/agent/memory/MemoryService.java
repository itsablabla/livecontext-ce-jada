package com.apimarketplace.agent.memory;

import com.apimarketplace.agent.domain.AgentMemoryEntity;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemorySource;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemoryType;
import com.apimarketplace.agent.repository.AgentMemoryRepository;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD for long-term memory, with the two invariants that make the feature
 * safe to inject into every prompt: every write is scanned
 * ({@link MemoryContentGuard}) and every write is capped
 * ({@link MemoryLimitsConfig}).
 *
 * <p><b>Save is an upsert on the slug, not an insert.</b> Two agents that
 * notice the same fact in the same workspace must converge on one row rather
 * than race to create two, and an agent correcting a fact it stored last week
 * must overwrite it rather than leave the workspace holding both the old and
 * the new version - a memory index containing a claim and its contradiction is
 * worse than no memory. The database backs this with two partial unique
 * indexes; this method is the cooperative path that reaches the same state
 * without provoking a constraint violation.
 */
@Service
@Transactional
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** Exposed so the agent-facing schema quotes the real cap rather than a copy of it. */
    public static final int MAX_TITLE_CHARS = 120;
    private static final int MAX_SLUG_CHARS = 80;
    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG_CHARS = 32;

    private final AgentMemoryRepository repository;
    private final MemoryLimitsConfig limits;

    public MemoryService(AgentMemoryRepository repository, MemoryLimitsConfig limits) {
        this.repository = repository;
        this.limits = limits;
    }

    /** Raised for anything the caller can fix by changing its input. Mapped to 400 / a tool error. */
    public static class MemoryValidationException extends RuntimeException {
        public MemoryValidationException(String message) { super(message); }
    }

    /**
     * Raised when the addressed memory is not in the caller's workspace, whether
     * because it does not exist or because it belongs to someone else. One
     * exception for both, mapped to 404 - a distinct "forbidden" would confirm
     * the row exists to someone outside the workspace that owns it.
     */
    public static class MemoryNotFoundException extends RuntimeException {
        public MemoryNotFoundException(String message) { super(message); }
    }

    /** Raised when a VIEWER attempts a write. Mapped to 403. */
    public static class MemoryWriteForbiddenException extends RuntimeException {
        public MemoryWriteForbiddenException(String message) { super(message); }
    }

    /**
     * What a caller asks to store. A record rather than a nine-argument method
     * because {@code save} is called from the REST controller, the MCP tool and
     * the tests, and a positional argument list of that width is where a
     * {@code title}/{@code summary} swap hides indefinitely.
     *
     * <p>{@code agentId} null means workspace scope. {@code slug} null means
     * "derive it from the title", which is what the agent does on a first save.
     */
    public record SaveRequest(
        String tenantId,
        String organizationId,
        UUID agentId,
        String slug,
        String title,
        String summary,
        String content,
        MemoryType type,
        List<String> tags,
        Boolean pinned,
        MemorySource source,
        UUID createdByAgentId
    ) {}

    // ==================== Writes ====================

    /**
     * Create or update the memory identified by (workspace, agent scope, slug).
     *
     * @param orgRole caller's role in the active workspace; a VIEWER is refused
     * @return the persisted row and whether it replaced one that was already there
     */
    public SaveOutcome save(SaveRequest request, String orgRole) {
        return doSave(request, orgRole);
    }

    /**
     * What a save did, not merely what it stored.
     *
     * <p>The upsert is keyed on a slug DERIVED from the title, so an agent saving
     * "Deploy cadence" replaces any entry that derives to the same handle, whoever
     * wrote it. The row alone cannot tell the caller that happened: it comes back
     * looking exactly like a fresh insert. An agent told only "SAVED" therefore
     * cannot know it has just overwritten a fact a PERSON typed, and cannot say so.
     *
     * <p>{@code replacedSource} is the authorship of the row BEFORE this write, so
     * "you replaced something a person wrote" is distinguishable from "you corrected
     * your own earlier note", which are different things to report back.
     *
     * @param entity         the persisted row
     * @param created        true when this write inserted; false when it replaced
     * @param replacedTitle  the title this write overwrote, or null on an insert
     * @param replacedSource who had written the row this write overwrote, or null
     */
    public record SaveOutcome(
        AgentMemoryEntity entity,
        boolean created,
        String replacedTitle,
        MemorySource replacedSource
    ) {}

    /**
     * Store a memory that must NOT already exist, refusing a collision.
     *
     * <p>{@link #save} is an upsert, which is right for an agent: it re-derives a
     * fact it already recorded and the second save is a correction. It is wrong for
     * a person pressing "new". They are not correcting anything, they have no idea
     * another entry derives to the same handle, and their form posts every field -
     * so an accidental title collision silently erased the existing body, unpinned
     * the entry, reset its type and re-attributed it, under a "saved" toast.
     *
     * <p>The refusal carries the existing entry so the caller can offer to open it.
     */
    public AgentMemoryEntity create(SaveRequest request, String orgRole) {
        assertNotViewer(orgRole, "save");
        String orgId = requireOrg(request.organizationId());
        // SANITISED, like save does. Deriving the collision check from the raw title
        // while save derives the stored slug from the sanitised one is a hole in this
        // very guard: a title pasted from a web page carrying a zero-width character
        // normalises to a different handle here than the one save writes, so the
        // check finds nothing and save then upserts over the existing row - exactly
        // the silent overwrite this method exists to prevent.
        String slug = normalizeSlug(request.slug() != null && !request.slug().isBlank()
            ? MemoryContentGuard.sanitizeSingleLine(request.slug())
            : MemoryContentGuard.sanitizeSingleLine(requireText(request.title(), "title", MAX_TITLE_CHARS)));

        Optional<AgentMemoryEntity> clash = findBySlug(orgId, request.agentId(), slug);
        if (clash.isPresent()) {
            throw new MemoryConflictException(clash.get());
        }
        // The entity only: a create cannot have replaced anything, so the outcome
        // carries nothing this caller could act on.
        return save(request, orgRole).entity();
    }

    /**
     * A "new" write whose handle is already taken.
     *
     * <p>The MESSAGE names the entry in the way, by title and by handle, because
     * that is what a person reads: told only that "a memory collided", they are
     * left to work out which one among two hundred. It used to also carry the
     * entity, which no caller ever read.
     */
    public static class MemoryConflictException extends RuntimeException {

        public MemoryConflictException(AgentMemoryEntity existing) {
            super("A memory titled '" + existing.getTitle() + "' already exists in this workspace "
                + "(" + existing.getSlug() + "). Open that entry and correct it, or use a different "
                + "title: saving over it here would replace what it holds.");
        }
    }

    private SaveOutcome doSave(SaveRequest request, String orgRole) {
        assertNotViewer(orgRole, "save");
        String orgId = requireOrg(request.organizationId());

        // Sanitise BEFORE validating, not after. The other order accepted a title or
        // summary made ENTIRELY of invisible characters: "\u200B" is not isBlank(), so
        // requireText passed it, and the sanitiser then reduced it to "". The row
        // stored an empty summary and rendered as "- [project] slug: " on every
        // agent's index line, or an empty title in the tab, with no refusal anywhere.
        // Cleaning first means the emptiness check sees what will actually be stored.
        //
        // It also has to precede the SCAN: a payload hiding behind zero-width
        // characters must not slip past a regex that never sees it joined up.
        //
        // The title and the summary are SINGLE-LINE: the index prints one line per
        // entry, so a summary carrying newlines printed as several lines inside the
        // fence and one entry could pose as several. The body keeps its line breaks,
        // because a body is prose and is never rendered into the index.
        String title = requireText(
            MemoryContentGuard.sanitizeSingleLine(request.title()), "title", MAX_TITLE_CHARS);
        String summary = requireText(
            MemoryContentGuard.sanitizeSingleLine(request.summary()), "summary", limits.getMaxSummaryChars());
        String content = MemoryContentGuard.sanitize(
            optionalText(request.content(), "content", limits.getMaxContentChars()));

        // Sanitised on both branches, and identically to the collision check in
        // create(): deriving the stored handle from raw text there and clean text
        // here means a title carrying an invisible character resolves to two
        // different slugs, the check finds nothing, and the save overwrites the row
        // the check exists to protect.
        String slug = normalizeSlug(request.slug() != null && !request.slug().isBlank()
            ? MemoryContentGuard.sanitizeSingleLine(request.slug())
            : title);
        List<String> tags = request.tags() != null ? normalizeTags(request.tags()) : null;

        // The slug and the tags reach the model too - the slug is rendered on every
        // index line, the tags come back from a get - so they are scanned like the
        // prose. An unscanned field that lands in the prompt is not a smaller hole
        // than an unscanned body, only a narrower one.
        Optional<String> threat = MemoryContentGuard.scanAll(
            // The slug is scanned in BOTH shapes, and it is the DERIVED slug, not the
            // one the caller may or may not have sent. Every threat pattern expects
            // whitespace between words and a slug has none, so the raw form hides a
            // payload that a model reads perfectly well: a hyphen reads as a space.
            //
            // Scanning only request.slug() left the common case wide open, because a
            // first save does not send one - the slug comes from the TITLE. So
            // title="Ignore-all-previous-instructions" was accepted and then printed
            // on every agent's index line in the workspace. The derived slug covers
            // both routes at once, since it is what actually gets rendered.
            concatForScan(tags, title, summary, content, slug, spaced(slug)));
        if (threat.isPresent()) {
            log.warn("[MEMORY] Rejected write in org {} (agent scope {}): threat={}",
                orgId, request.agentId(), threat.get());
            throw new MemoryValidationException(MemoryContentGuard.rejectionMessage(threat.get()));
        }

        Optional<AgentMemoryEntity> existing = findBySlug(orgId, request.agentId(), slug);

        // null means KEEP on an upsert, like every other optional field. It used to
        // mean ERASE, which made the documented correction call (same slug, new
        // summary, no content) silently destroy an 8000-character body. On a create
        // there is nothing to keep, so it stays an empty body.
        String effectiveContent = content != null
            ? content
            : existing.map(AgentMemoryEntity::getContent).orElse("");

        Boolean effectivePinned = request.pinned() != null
            ? request.pinned()
            : existing.map(AgentMemoryEntity::getPinned).orElse(false);
        // A row that will stay switched off is not rendered anywhere, so it holds
        // no pin slot and no characters. update() already skips the check for that
        // case; save did not, so re-saving a deactivated entry that had been pinned
        // was refused for competing with pins it does not actually compete with -
        // and the documented correction flow is exactly that re-save.
        boolean willRender = existing.isEmpty() || !Boolean.FALSE.equals(existing.get().getIsActive());
        assertPinnableIfPinned(willRender && Boolean.TRUE.equals(effectivePinned),
            effectiveContent, orgId, request.agentId(),
            existing.map(AgentMemoryEntity::getId).orElse(null), viewerOf(request));

        // Captured BEFORE the overwrite, and before the source is re-badged below.
        String replacedTitle = existing.map(AgentMemoryEntity::getTitle).orElse(null);
        MemorySource replacedSource = existing.map(AgentMemoryEntity::getSource).orElse(null);
        String previousSummary = existing.map(AgentMemoryEntity::getSummary).orElse(null);
        String previousContent = existing.map(AgentMemoryEntity::getContent).orElse(null);

        AgentMemoryEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            assertScopeHasRoom(orgId, request.agentId());
            entity = new AgentMemoryEntity();
            entity.setOrganizationId(orgId);
            entity.setTenantId(request.tenantId());
            entity.setAgentId(request.agentId());
            entity.setSlug(slug);
            entity.setSource(request.source() != null ? request.source() : MemorySource.AGENT);
            entity.setCreatedByAgentId(request.createdByAgentId());
        }

        entity.setTitle(title);
        entity.setSummary(summary);
        entity.setContent(effectiveContent);
        if (request.type() != null) {
            entity.setType(request.type());
        }
        if (tags != null) {
            entity.setTags(tags);
        }
        if (request.pinned() != null) {
            entity.setPinned(request.pinned());
        }
        // A NEW row starts active. An EXISTING one keeps whatever it has: deactivating
        // is a person's veto on a fact, and an agent that re-derives the same fact would
        // otherwise switch it back on by simply saving it again - which is the one thing
        // the person was trying to prevent. The correction is still stored, so turning
        // the entry back on later yields the corrected text rather than the old one.
        if (existing.isEmpty()) {
            entity.setIsActive(true);
        }

        // Authorship follows a real text CHANGE, exactly as update() does. An agent
        // overwriting a person's entry makes the stored words the agent's, so the
        // badge has to move; re-saving text that is byte-identical changes nothing a
        // reader would see, so it must not. The two write paths used to disagree
        // here: update() compared the text while save() re-badged on every upsert, so
        // an agent re-deriving, unchanged, a fact a person had typed flipped the
        // badge. Compared against the values captured ABOVE, because the setters
        // above have already overwritten the entity.
        boolean textChanged = existing.isPresent()
            && (!title.equals(replacedTitle)
                || !summary.equals(previousSummary)
                || !java.util.Objects.equals(effectiveContent, previousContent));
        if (textChanged && request.source() != null && request.source() != entity.getSource()) {
            entity.setSource(request.source());
        }

        AgentMemoryEntity saved;
        try {
            saved = repository.saveAndFlush(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // ONLY the slug race gets the retry advice. Any other constraint
            // failure - a summary longer than the column once an operator raises
            // max-summary-chars past 240, say - would otherwise be reported as "a
            // concurrent write landed, save it again", and the caller would retry
            // the identical too-long text forever.
            if (!isSlugUniquenessViolation(e)) {
                throw e;
            }
            // Two callers saved the same NEW slug at once: both read nothing, both
            // inserted, and one of the partial unique indexes refused the loser. The
            // lookup above resolves every non-concurrent case, so this is the narrow
            // window where two writes to one slug overlap in the same workspace.
            //
            // The loser is told to retry rather than merged in-process, deliberately.
            // Merging would mean re-reading and writing AFTER a failed flush, and a
            // flush failure leaves the persistence context unusable, so the merge has
            // to run in a NEW transaction. In Spring that means a separate bean:
            // annotating a method on THIS class and calling it from here is
            // self-invocation, which bypasses the proxy, so the annotation would do
            // nothing and the merge would fail on the broken EntityManager while
            // looking correct in a mock-based test. (The codebase already puts
            // REQUIRES_NEW work in its own bean for exactly this reason - see
            // CatalogSyncMergeRunner and ModelCatalogSyncLogWriter.) A whole bean is
            // not worth it here: the colliding writes carry near-identical text by
            // construction, and a retry converges on the winner's row anyway.
            log.info("[MEMORY] Concurrent first save of '{}' in org {}; asking the caller to retry", slug, orgId);
            throw new MemoryValidationException(
                "Another write to the memory '" + slug + "' landed at the same moment, so this one was not "
                + "stored. Save it again: the second attempt will update the existing entry instead of "
                + "creating a duplicate.");
        }
        log.info("[MEMORY] {} '{}' in org {} (agent scope {})",
            existing.isPresent() ? "Updated" : "Created", slug, orgId, request.agentId());
        return new SaveOutcome(saved, existing.isEmpty(), replacedTitle, replacedSource);
    }

    /**
     * Update an existing memory addressed by id. Null fields mean "leave
     * unchanged", matching how {@code SkillService.updateSkill} behaves, so a
     * caller that only wants to pin an entry does not have to re-send its body
     * and cannot blank it by omission.
     */
    public AgentMemoryEntity update(UUID id, String orgId, String orgRole,
                                    String title, String summary, String content,
                                    MemoryType type, List<String> tags, Boolean pinned, Boolean isActive,
                                    MemorySource editorSource) {
        assertNotViewer(orgRole, "update");
        AgentMemoryEntity entity = requireInScope(id, orgId);

        // Sanitised BEFORE the emptiness and length checks, exactly as save() does,
        // and single-line for the two fields the index renders on one line. Cleaning
        // them differently on the two write paths would make the tab the way around
        // the guard: refused on a save, accepted on an edit.
        String nextTitle = title != null
            ? requireText(MemoryContentGuard.sanitizeSingleLine(title), "title", MAX_TITLE_CHARS)
            : entity.getTitle();
        String nextSummary = summary != null
            ? requireText(MemoryContentGuard.sanitizeSingleLine(summary), "summary", limits.getMaxSummaryChars())
            : entity.getSummary();
        String nextContent = content != null
            ? MemoryContentGuard.sanitize(optionalText(content, "content", limits.getMaxContentChars()))
            : entity.getContent();

        List<String> nextTags = tags != null ? normalizeTags(tags) : null;
        Optional<String> threat = MemoryContentGuard.scanAll(
            concatForScan(nextTags, nextTitle, nextSummary, nextContent,
                entity.getSlug(), spaced(entity.getSlug())));
        if (threat.isPresent()) {
            log.warn("[MEMORY] Rejected update of {} in org {}: threat={}", id, orgId, threat.get());
            throw new MemoryValidationException(MemoryContentGuard.rejectionMessage(threat.get()));
        }

        // An inactive row is not in the injected block at all, so it occupies no
        // pinned budget and must not be measured against one: checking it refused
        // edits to a deactivated entry that was pinned before it was deactivated.
        boolean willBeActive = isActive != null ? isActive : Boolean.TRUE.equals(entity.getIsActive());
        // The viewer is derived from who is editing, not assumed to be a person.
        // A refusal names the entries holding the pinned slots, and describePinned
        // hides the ones the viewer cannot see: hard-coding "person" here would
        // print another agent's private slugs the moment anything but the REST
        // surface routed an edit through this method.
        assertPinnableIfPinned(
            willBeActive && (pinned != null ? pinned : Boolean.TRUE.equals(entity.getPinned())),
            nextContent, orgId, entity.getAgentId(), entity.getId(),
            editorSource == MemorySource.USER || editorSource == null
                ? Viewer.person()
                // NOT the entry's scope: that is the row being edited, not whoever is
                // editing it, and using it would tell an agent that its OWN pins
                // belong to somebody else. This method has no caller identity to work
                // from (it is the person's surface), so the conservative viewer is the
                // one that sees workspace rows only: it can never name a private entry
                // it should not. An agent path added later must thread its own id.
                : Viewer.agent(null));

        // Read before the setters land, so the comparison below is against what was
        // actually stored rather than against what we just wrote.
        String previousTitle = entity.getTitle();
        String previousSummary = entity.getSummary();
        String previousContent = entity.getContent();

        entity.setTitle(nextTitle);
        entity.setSummary(nextSummary);
        entity.setContent(nextContent == null ? "" : nextContent);
        if (type != null) entity.setType(type);
        if (nextTags != null) entity.setTags(nextTags);
        if (pinned != null) entity.setPinned(pinned);
        if (isActive != null) entity.setIsActive(isActive);

        // The badge answers "who wrote the text I am reading", not "who created the
        // row". A person correcting an agent's entry has authored what is now stored,
        // and leaving it labelled as agent-written would make the badge worse than
        // absent: it would attribute the human's words to the agent. Only a real text
        // edit re-attributes; toggling `pinned` or `isActive` leaves the author alone.
        //
        // The test is whether the text CHANGED, not whether a text field was sent. An
        // editor form posts every field on every save, including the ones nobody
        // touched, so keying off presence re-attributed an agent's entry to the person
        // who did nothing but tick "always in context" - the exact case the paragraph
        // above says must leave the author alone.
        boolean textChanged =
            (title != null && !java.util.Objects.equals(nextTitle, previousTitle))
            || (summary != null && !java.util.Objects.equals(nextSummary, previousSummary))
            || (content != null && !java.util.Objects.equals(
                    nextContent == null ? "" : nextContent, previousContent));
        if (editorSource != null && textChanged) {
            entity.setSource(editorSource);
        }

        return repository.save(entity);
    }

    /** Hard delete. Memory is not a document with revisions; a fact the user removed should stop being injected. */
    public void delete(UUID id, String orgId, String orgRole) {
        assertNotViewer(orgRole, "delete");
        AgentMemoryEntity entity = requireInScope(id, orgId);
        repository.delete(entity);
        log.info("[MEMORY] Deleted '{}' in org {}", entity.getSlug(), orgId);
    }

    // ==================== Reads ====================

    /**
     * Read one entry by id, restricted to what the CALLING AGENT may see.
     *
     * <p>The org-only lookups ({@link #findInScope}, {@link #delete}) are the human
     * surface: a person auditing the Memory tab has to see the agent-private rows
     * too, or the ones they cannot see are exactly the ones they cannot fix. An
     * AGENT must not: private means private from its siblings.
     *
     * <p>Also records the recall. The counter is what turns "this workspace has 200
     * memories" into "these 40 are the ones agents actually reach for", the only
     * evidence a person has when pruning. It is bumped on a read rather than during
     * index injection because every entry appears in the index on every run, so
     * counting that would count nothing.
     *
     * <p>Without this the id path was a way around it. UUIDs are not secret here -
     * the tab shows one per row, {@code save} returns one, and the metrics
     * aggregator records one - so "you need the id" is not a control. Any agent in
     * the workspace could have read and deleted a sibling's private memory.
     */
    public Optional<AgentMemoryEntity> getByIdVisibleToAgent(UUID id, String orgId, UUID callerAgentId) {
        Optional<AgentMemoryEntity> found = repository.findByIdAndOrganizationIdStrict(id, requireOrg(orgId))
            .filter(m -> isVisibleToAgent(m, callerAgentId))
            .filter(MemoryService::isActive);
        found.ifPresent(this::recordRecall);
        return found;
    }

    /** Resolve by id for a DELETE, without counting it as a recall. */
    @Transactional(readOnly = true)
    public Optional<AgentMemoryEntity> findByIdVisibleToAgent(UUID id, String orgId, UUID callerAgentId) {
        return repository.findByIdAndOrganizationIdStrict(id, requireOrg(orgId))
            .filter(m -> isVisibleToAgent(m, callerAgentId))
            .filter(MemoryService::isActive);
    }

    /**
     * Whether an entry is switched on.
     *
     * <p>Every AGENT-facing read is filtered through this, and no PERSON-facing one
     * is. A deactivated entry is not deleted: it keeps its text, its slug and its
     * history, it just stops being injected, recalled, searched or addressable by
     * the agent. The tab keeps showing it so the person who switched it off is the
     * one who decides whether it comes back.
     */
    private static boolean isActive(AgentMemoryEntity memory) {
        return !Boolean.FALSE.equals(memory.getIsActive());
    }

    /** Workspace rows are visible to every agent; an agent-scoped row only to its own agent. */
    private static boolean isVisibleToAgent(AgentMemoryEntity memory, UUID callerAgentId) {
        return memory.getAgentId() == null || memory.getAgentId().equals(callerAgentId);
    }

    /**
     * Resolve a slug for a DELETE. Same scope resolution as a read, but it does not
     * count a recall: a delete is not the agent reaching for the fact, and counting
     * it would pollute the only signal a person has for deciding what to prune.
     */
    @Transactional(readOnly = true)
    public Optional<AgentMemoryEntity> findBySlugVisibleToAgent(String orgId, UUID callerAgentId, String slug) {
        String normalized = normalizeSlug(slug);
        Optional<AgentMemoryEntity> found = callerAgentId != null
            ? repository.findAgentSlugStrict(requireOrg(orgId), callerAgentId, normalized).filter(MemoryService::isActive)
            : Optional.empty();
        return found.isPresent()
            ? found
            : repository.findWorkspaceSlugStrict(requireOrg(orgId), normalized).filter(MemoryService::isActive);
    }

    /**
     * Delete an entry an AGENT addressed, refusing one that belongs to a sibling.
     * The org-only {@link #delete} stays the human surface.
     */
    public void deleteVisibleToAgent(UUID id, String orgId, String orgRole, UUID callerAgentId) {
        assertNotViewer(orgRole, "delete");
        AgentMemoryEntity entity = repository.findByIdAndOrganizationIdStrict(id, requireOrg(orgId))
            .filter(m -> isVisibleToAgent(m, callerAgentId))
            .filter(MemoryService::isActive)
            .orElseThrow(() -> new MemoryNotFoundException("Memory not found: " + id));
        repository.delete(entity);
        log.info("[MEMORY] Deleted '{}' in org {} (agent scope {})", entity.getSlug(), orgId, entity.getAgentId());
    }

    /**
     * Resolve a slug the way a READ should: across everything the caller can see.
     *
     * <p>The injected index mixes the workspace's shared entries with the calling
     * agent's private ones and marks neither, so an agent reading a line off its own
     * index has no way to know which scope it came from. Resolving only one scope
     * therefore made the index advertise entries that {@code get} then reported as
     * not found. Agent scope is tried first because it is the more specific of the
     * two; a caller with no agent bound only ever sees workspace rows.
     */
    public Optional<AgentMemoryEntity> getBySlugAndRecordRecall(String orgId, UUID callerAgentId, String slug) {
        String normalized = normalizeSlug(slug);
        Optional<AgentMemoryEntity> found = callerAgentId != null
            ? repository.findAgentSlugStrict(requireOrg(orgId), callerAgentId, normalized).filter(MemoryService::isActive)
            : Optional.empty();
        if (found.isEmpty()) {
            found = repository.findWorkspaceSlugStrict(requireOrg(orgId), normalized).filter(MemoryService::isActive);
        }
        found.ifPresent(this::recordRecall);
        return found;
    }

    /**
     * Count one recall, leaving {@code updated_at} alone.
     *
     * <p>A targeted update, not an entity save: saving would fire {@code @PreUpdate}
     * and stamp {@code updated_at}, so the tab's "updated" line would show the last
     * time an agent READ the entry instead of the last time anyone changed it, and
     * the injection ordering ({@code updated_at DESC}) would reshuffle on every read.
     * The in-memory copy is updated too so the caller returns current values.
     */
    private void recordRecall(AgentMemoryEntity entity) {
        java.time.Instant now = java.time.Instant.now();
        repository.recordRecall(entity.getId(), now);
        entity.setRecallCount(entity.getRecallCount() == null ? 1 : entity.getRecallCount() + 1);
        entity.setLastRecalledAt(now);
    }

    /**
     * Entries visible to a RUN: the workspace's shared memory plus this agent's
     * private memory, switched-off ones excluded.
     *
     * <p>The person's list is {@link #listWorkspace}, which is a different question
     * and gets a different answer: it keeps the switched-off rows, because the tab
     * is the only screen that can switch one back on. There used to be a third
     * shape (this list with the inactive rows kept) behind an unused REST query
     * parameter; nothing ever asked for it.
     */
    @Transactional(readOnly = true)
    public List<AgentMemoryEntity> listVisible(String orgId, UUID agentId) {
        return repository.findVisibleStrict(requireOrg(orgId), agentId).stream()
            .filter(MemoryService::isActive)
            .toList();
    }

    /** Every entry in the workspace, both scopes. What the Memory tab shows by default. */
    @Transactional(readOnly = true)
    public List<AgentMemoryEntity> listWorkspace(String orgId) {
        return repository.findAllInWorkspaceStrict(requireOrg(orgId));
    }

    @Transactional(readOnly = true)
    public List<AgentMemoryEntity> search(String orgId, UUID agentId, String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return repository.searchStrict(requireOrg(orgId),
            agentId != null ? agentId.toString() : null,
            query, Math.max(1, Math.min(maxResults, 50)));
    }

    /**
     * Search every row in the workspace, both scopes. The Memory tab's search.
     *
     * <p>Separate from {@link #search} on purpose: an AGENT must not find a
     * sibling's private entry, but a PERSON auditing memory must, or the rows they
     * can see in the list are ones they can never search for, which for a list this
     * long is the same as not having them.
     */
    @Transactional(readOnly = true)
    public List<AgentMemoryEntity> searchWorkspace(String orgId, String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return repository.searchWorkspaceStrict(requireOrg(orgId), query, Math.max(1, Math.min(maxResults, 50)));
    }

    /**
     * The index lines the prompt section renders: active, in scope, pinned first,
     * and WITHOUT the bodies. This runs at the start of every agent execution in
     * the workspace, so it deliberately projects only what an index line needs.
     */
    @Transactional(readOnly = true)
    public List<AgentMemoryRepository.IndexRow> findIndexForInjection(String orgId, UUID agentId, int limit) {
        return repository.findIndexForInjectionStrict(orgId, agentId, PageRequest.of(0, Math.max(1, limit)));
    }

    /** The handful of pinned entries whose full body is injected. */
    @Transactional(readOnly = true)
    public List<AgentMemoryEntity> findPinnedForInjection(String orgId, UUID agentId, int limit) {
        return repository.findPinnedForInjectionStrict(orgId, agentId, PageRequest.of(0, Math.max(1, limit)));
    }

    @Transactional(readOnly = true)
    public Optional<AgentMemoryEntity> findInScope(UUID id, String orgId) {
        return repository.findByIdAndOrganizationIdStrict(id, requireOrg(orgId));
    }

    // ==================== Helpers ====================

    private Optional<AgentMemoryEntity> findBySlug(String orgId, UUID agentId, String slug) {
        return agentId == null
            ? repository.findWorkspaceSlugStrict(orgId, slug)
            : repository.findAgentSlugStrict(orgId, agentId, slug);
    }

    private AgentMemoryEntity requireInScope(UUID id, String orgId) {
        return repository.findByIdAndOrganizationIdStrict(id, requireOrg(orgId))
            .orElseThrow(() -> new MemoryNotFoundException("Memory not found: " + id));
    }

    /**
     * Refuse a save that would take the scope past its row cap.
     *
     * <p>Count-then-insert, so two concurrent FIRST saves can both pass and leave
     * the scope one over. Deliberate: there is no database constraint to lean on
     * (unlike the slug race just below, which a partial unique index catches), the
     * overshoot is bounded by the number of simultaneous writers, and the cap is a
     * budget rather than an invariant - one extra row costs a few hundred
     * characters of prompt, while a lock on every save would cost every save. If
     * this ever needs to be exact, the fix is a counter column with a check
     * constraint, not a transaction-level lock here.
     */
    private void assertScopeHasRoom(String orgId, UUID agentId) {
        if (agentId == null) {
            long count = repository.countByOrganizationIdAndAgentIdIsNull(orgId);
            if (count >= limits.getMaxWorkspaceEntries()) {
                throw new MemoryValidationException(
                    "This workspace already holds the maximum of " + limits.getMaxWorkspaceEntries()
                    + " shared memories. Delete an entry that is no longer true before saving a new one. "
                    + "Entries that are switched off still count towards this limit, so switching one off "
                    + "does not make room; only deleting does.");
            }
        } else {
            long count = repository.countByOrganizationIdAndAgentId(orgId, agentId);
            if (count >= limits.getMaxAgentEntries()) {
                throw new MemoryValidationException(
                    "This agent already holds the maximum of " + limits.getMaxAgentEntries()
                    + " private memories. Delete an entry that is no longer true before saving a new one. "
                    + "Entries that are switched off still count towards this limit, so switching one off "
                    + "does not make room; only deleting does.");
            }
        }
    }

    private void assertNotViewer(String orgRole, String op) {
        if (orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim())) {
            throw new MemoryWriteForbiddenException(
                "Your role in this workspace is read-only, so you cannot " + op + " a memory.");
        }
    }

    private static String requireOrg(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            // Fall back to the request/async binding before giving up: service-layer
            // callers on a daemon thread get it from TenantResolver.runWithOrgScope.
            String bound = TenantResolver.currentRequestOrganizationId();
            if (bound != null && !bound.isBlank()) {
                return bound;
            }
            throw new MemoryValidationException("No active workspace on this request; memory is workspace-scoped.");
        }
        return orgId;
    }

    private static String requireText(String value, String field, int maxChars) {
        if (value == null || value.isBlank()) {
            throw new MemoryValidationException(field + " is required.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxChars) {
            throw new MemoryValidationException(
                field + " is " + trimmed.length() + " characters; the maximum is " + maxChars
                + ". Memory entries are one fact each, so a long one usually wants splitting in two.");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxChars) {
            throw new MemoryValidationException(
                field + " is " + trimmed.length() + " characters; the maximum is " + maxChars + ".");
        }
        return trimmed;
    }

    /**
     * Every field that reaches a model, flattened for the scanner.
     *
     * <p>Varargs on the leading fields rather than a fixed list, because the slug is
     * scanned twice: once as stored and once with its separators read as spaces. An
     * unscanned field that lands in the prompt is not a smaller hole than an
     * unscanned body, only a narrower one.
     */
    private static String[] concatForScan(List<String> tags, String... texts) {
        List<String> fields = new ArrayList<>(List.of());
        for (String text : texts) {
            fields.add(text);
        }
        if (tags != null) {
            fields.addAll(tags);
        }
        return fields.toArray(new String[0]);
    }

    /**
     * Refuse a pin the renderer would silently drop.
     *
     * <p>Pinning promises the full body in every run's context, but the pinned block
     * has its own character budget, and a body over it is demoted back to a one-line
     * index entry. Accepting the pin anyway would leave the UI showing a "Pinned"
     * badge on an entry that is not pinned in any sense the model can observe, which
     * is worse than refusing: the person would have no way to find out.
     */
    private void assertPinnableIfPinned(Boolean pinned, String content, String orgId, UUID agentId,
                                        UUID selfId, Viewer viewer) {
        if (!Boolean.TRUE.equals(pinned)) {
            return;
        }
        // Pinning means "inject the BODY on every run". An entry with no body has
        // nothing to inject, so the renderer skips it - while it still consumes one
        // of the handful of pin slots for everyone in the workspace and still shows
        // a "Pinned" badge. That is exactly the "a pin that does nothing" outcome
        // this whole check exists to prevent, so it is refused at the door.
        if (content == null || content.isBlank()) {
            throw new MemoryValidationException(
                "Pinning keeps the BODY of a memory in context on every run, and this entry has no body, "
                + "so pinning it would take one of the " + limits.getMaxPinnedEntries() + " slots and add "
                + "nothing. Either write the detail into the body, or leave it unpinned: its summary is "
                + "carried either way.");
        }
        // The renderer wraps every pinned entry in "\n### " + title + "\n" + body +
        // "\n", so the block spends more than the bodies. Reserve that per entry
        // rather than a flat allowance: with three entries and long titles the flat
        // 200 was not enough and the third body was still dropped at render.
        int perEntryOverhead = MAX_TITLE_CHARS + 8;
        // Floor at zero: a block sized smaller than its own per-entry overhead would
        // otherwise produce a negative budget and refuse every pin with "has to fit
        // -96 characters", which reads as a bug rather than as a misconfiguration.
        // The section heading counts too. Reserving only the per-entry framing left
        // the write side about twenty characters more generous than the renderer, so
        // a set accepted right at the aggregate boundary had its LAST entry dropped
        // at render - a pin that did nothing, which is the exact outcome this whole
        // check exists to prevent. It only shows up with several entries at once,
        // which is why a single-entry boundary test never saw it.
        int budget = Math.max(0,
            limits.getPinnedBlockChars()
                - MemoryPromptSection.PINNED_HEADING_CHARS
                - (perEntryOverhead * limits.getMaxPinnedEntries()));
        if (content != null && content.length() > budget) {
            throw new MemoryValidationException(
                "This memory is " + content.length() + " characters, and a pinned entry has to fit "
                + budget + " because its whole body is added to every run. Either shorten it, or leave it "
                + "unpinned so only its summary is carried and the body is fetched on demand.");
        }

        // And the COUNT, not just this one body. The renderer takes the newest
        // maxPinnedEntries rows and stops; accepting a further pin would demote the
        // oldest one to an index line while the tab kept a "Pinned" badge on both.
        // The aggregate matters too: three bodies that each fit can overflow the
        // block together.
        //
        // Checked against EVERY scope this entry will be rendered into, not just its
        // own. A workspace pin is injected for every agent, so it competes with each
        // agent's private pins separately; measuring it against the workspace pins
        // alone accepted a pin that demoted some agent's oldest one, for that agent
        // only, with both badges still showing. One unfiltered read grouped here is
        // cheaper than the per-scope query it replaces.
        List<AgentMemoryEntity> workspacePinned = new ArrayList<>();
        java.util.Map<UUID, List<AgentMemoryEntity>> pinnedByAgent = new java.util.LinkedHashMap<>();
        for (AgentMemoryEntity pinnedRow : repository.findAllPinnedInWorkspaceStrict(orgId)) {
            if (selfId != null && selfId.equals(pinnedRow.getId())) {
                continue;
            }
            if (pinnedRow.getAgentId() == null) {
                workspacePinned.add(pinnedRow);
            } else {
                pinnedByAgent.computeIfAbsent(pinnedRow.getAgentId(), k -> new ArrayList<>()).add(pinnedRow);
            }
        }

        // The competing sets: this agent's own union for an agent-scoped pin, or one
        // union per agent (plus the bare workspace, for a chat with no agent bound)
        // for a workspace-scoped pin.
        List<List<AgentMemoryEntity>> competingSets = new ArrayList<>();
        if (agentId != null) {
            List<AgentMemoryEntity> union = new ArrayList<>(workspacePinned);
            union.addAll(pinnedByAgent.getOrDefault(agentId, List.of()));
            competingSets.add(union);
        } else {
            competingSets.add(workspacePinned);
            for (List<AgentMemoryEntity> ofOneAgent : pinnedByAgent.values()) {
                List<AgentMemoryEntity> union = new ArrayList<>(workspacePinned);
                union.addAll(ofOneAgent);
                competingSets.add(union);
            }
        }

        // The tightest scope decides: it is the one where the entry would be dropped.
        // The two limits are measured on DIFFERENT axes, so each picks its own binding
        // set: the set with the most entries binds the count, the set spending the most
        // characters binds the aggregate. They are frequently not the same set - one
        // agent can hold three short pins while another holds two long ones - and
        // reporting the count set alongside a character overflow described entries that
        // had nothing to do with the refusal, including deciding from the wrong set
        // whether the caller is even able to act on it.
        List<AgentMemoryEntity> countBinding = competingSets.stream()
            .max(java.util.Comparator.comparingInt(List::size))
            .orElse(List.of());
        List<AgentMemoryEntity> charsBinding = competingSets.stream()
            .max(java.util.Comparator.comparingInt(MemoryService::pinnedChars))
            .orElse(List.of());
        int used = pinnedChars(charsBinding);

        if (countBinding.size() >= limits.getMaxPinnedEntries()) {
            throw new MemoryValidationException(
                "Only " + limits.getMaxPinnedEntries() + " memories can be kept fully in context at once, and "
                + "that many already are" + describePinned(countBinding, viewer)
                + ". Unpin one of those first, or leave this entry unpinned so its summary is carried and its "
                + "body fetched on demand.");
        }

        int incoming = content == null ? 0 : content.length();
        if (used + incoming > budget) {
            throw new MemoryValidationException(
                "The memories already kept fully in context use " + used + " characters, and adding this one ("
                + incoming + ") would exceed the " + budget + " available, so it would not actually be carried. "
                // Same audience problem as the count message: "unpin one of them" is
                // advice the caller cannot follow when the binding scope is another
                // agent's, so say who can - judged on the set that actually overflowed.
                + (charsBinding.stream().allMatch(viewer::canSee)
                    ? "Unpin one of them, shorten this entry, or leave it unpinned."
                    : "Some of them are pinned privately by another agent and only a person can unpin those, "
                      + "so shorten this entry or leave it unpinned."));
        }
    }

    /** Characters a set of pinned entries spends on bodies, which is what the block budget measures. */
    private static int pinnedChars(List<AgentMemoryEntity> set) {
        return set.stream().mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length()).sum();
    }

    /**
     * Name the entries holding the pinned slots, WITHOUT naming any the caller
     * cannot see.
     *
     * <p>The binding scope for a workspace-scope pin can be some agent's private
     * union, and a slug is derived from a title: printing that set would hand a
     * plain chat, or a different agent, the titles of another agent's private
     * memories. That is the boundary this class enforces everywhere else, and it
     * would be undone by an error message.
     *
     * <p>It would also be unactionable: the refused caller cannot open or unpin an
     * entry it cannot see, and its own index shows no pinned entry at all, so
     * "unpin one of those" would name things that, from where it stands, do not
     * exist. The count is reported instead, with who can act on it.
     */
    private static String describePinned(List<AgentMemoryEntity> binding, Viewer viewer) {
        List<String> visible = binding.stream()
            .filter(viewer::canSee)
            .map(AgentMemoryEntity::getSlug)
            .toList();
        int hidden = binding.size() - visible.size();

        StringBuilder description = new StringBuilder();
        if (!visible.isEmpty()) {
            description.append(" (").append(String.join(", ", visible));
            if (hidden > 0) {
                // The binding scope is always ONE agent's union, so the hidden rows
                // belong to a single agent: "other agents" would be wrong however
                // many there are.
                description.append(", plus ").append(hidden).append(" pinned privately by another agent");
            }
            description.append(')');
        } else if (hidden > 0) {
            description.append(" (").append(hidden)
                .append(" pinned privately by another agent, which only a person can unpin from the "
                    + "workspace's memory list)");
        }
        return description.toString();
    }

    /**
     * Who is asking, which is NOT the same as the scope being written into.
     *
     * <p>The distinction only shows up in a refusal, and it showed up wrong: the
     * check was handed the entry's scope, so an agent saving a WORKSPACE pin (scope
     * null) was told its own private pins were "pinned privately by another agent,
     * which only a person can unpin". Both halves were false, and the caller had no
     * way to know the advice did not apply to it.
     */
    private record Viewer(boolean seesEverything, UUID agentId) {

        /** A human on the REST surface: the Memory tab shows every row in the workspace. */
        static Viewer person() {
            return new Viewer(true, null);
        }

        /** An agent: workspace rows plus its own. A null id is a chat with no agent bound. */
        static Viewer agent(UUID agentId) {
            return new Viewer(false, agentId);
        }

        boolean canSee(AgentMemoryEntity memory) {
            return seesEverything
                || memory.getAgentId() == null
                || memory.getAgentId().equals(agentId);
        }
    }

    /**
     * The viewer behind a save. {@code source=USER} is the REST surface, which is a
     * person; anything else is the tool, where {@code createdByAgentId} carries the
     * calling agent (null for a plain chat).
     */
    private static Viewer viewerOf(SaveRequest request) {
        return request.source() == MemorySource.USER
            ? Viewer.person()
            : Viewer.agent(request.createdByAgentId());
    }

    /**
     * Parse a caller-supplied type name, case-insensitively.
     *
     * <p>Empty for a value that is not one of the four, absent for no value at all -
     * two different answers, because "the caller said nothing" and "the caller said
     * something wrong" deserve different treatment and collapsing them is how an
     * unrecognised type used to be filed silently as the default.
     *
     * <p>Lives here rather than on each surface so the accepted set cannot drift
     * between the tool and the REST endpoint; the two keep their own way of
     * reporting the refusal, which is the part that genuinely differs.
     */
    public static Optional<MemoryType> parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MemoryType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException notAType) {
            return Optional.empty();
        }
    }

    /** The four type names, lowercased, for a message that has to list them. */
    public static List<String> typeNames() {
        return java.util.Arrays.stream(MemoryType.values())
            .map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Whether a constraint failure is one of the two partial unique slug indexes.
     *
     * <p>Matched on the index NAME rather than on the exception type, because the
     * type is shared with every other constraint on the table and the advice that
     * follows ("save it again") is only true for this one.
     */
    private static boolean isSlugUniquenessViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("uq_agent_memories_")) {
                return true;
            }
        }
        return false;
    }

    /** A hyphen/underscore-separated handle re-read as words, so the threat patterns can match it. */
    private static String spaced(String raw) {
        return raw == null ? null : raw.replace('-', ' ').replace('_', ' ');
    }

    /**
     * Clean, deduplicate and CAP a tag list.
     *
     * <p>Over-length tags are cut, but too MANY tags is refused rather than
     * silently dropped. The two are not the same mistake: a cut tag is still the
     * tag the caller meant and still filters the way they expect, whereas an
     * eleventh tag that vanishes under a "saved" result is a filter the person
     * will later use and find empty, with nothing anywhere saying why.
     */
    private static List<String> normalizeTags(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String tag : raw) {
            if (tag == null || tag.isBlank()) continue;
            String cleaned = MemoryContentGuard.sanitizeSingleLine(tag).toLowerCase(Locale.ROOT);
            if (cleaned.length() > MAX_TAG_CHARS) {
                cleaned = cleaned.substring(0, MAX_TAG_CHARS);
            }
            if (!cleaned.isEmpty() && !out.contains(cleaned)) {
                out.add(cleaned);
            }
            if (out.size() > MAX_TAGS) {
                throw new MemoryValidationException(
                    "A memory takes at most " + MAX_TAGS + " tags and this one has more. Tags are for "
                    + "narrowing your own list, so keep the few you would actually filter by and put "
                    + "the rest of the detail in the body.");
            }
        }
        return out;
    }

    /**
     * Fold a title (or a caller-supplied slug) into the canonical handle:
     * lowercase, accents stripped, everything non-alphanumeric collapsed to a
     * single hyphen.
     *
     * <p>Normalising rather than validating is deliberate. The slug is the
     * upsert key, so if "Deploy Cadence", "deploy cadence" and "deploy-cadence"
     * produced three different keys, an agent re-saving a fact it had phrased
     * slightly differently would silently create a duplicate instead of
     * correcting the original. This mirrors what {@code LabelNormalizer} does
     * for workflow node keys, for the same reason.
     */
    static String normalizeSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MemoryValidationException("slug or title is required to identify a memory.");
        }
        // ONE path, not "ASCII first with a fallback for other scripts". The
        // fallback version only fired when the ASCII reduction came out EMPTY, so
        // any title carrying a single ASCII character kept just that fragment:
        // "会议 2024" and "预算 2024" both became "2024", and because save is an
        // upsert the second one silently OVERWROTE the first - a different fact,
        // replaced, reported as success. Keeping every Unicode letter and digit
        // treats scripts alike and cannot collide that way. Accents are still
        // folded first, so Latin titles keep the slugs they already had.
        //
        // NFC at the end because NFD leaves Hangul decomposed into conjoining
        // jamo, which is what would otherwise be stored and rendered.
        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String slug = Normalizer.normalize(
                decomposed.toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "-")
                    .replaceAll("^-+|-+$", ""),
                Normalizer.Form.NFC);

        if (slug.isEmpty()) {
            throw new MemoryValidationException(
                "'" + raw + "' contains no letters or digits, so it cannot identify a memory.");
        }
        if (slug.length() > MAX_SLUG_CHARS) {
            // Cut on a CODE POINT boundary. substring() counts UTF-16 units, so a cut
            // falling between the halves of a surrogate pair would leave an unpaired
            // one in the stored handle, which renders as a replacement character on
            // every index line the entry ever appears in. Scripts outside the basic
            // plane are exactly the ones this method was widened to support.
            int end = MAX_SLUG_CHARS;
            if (Character.isHighSurrogate(slug.charAt(end - 1))
                    && Character.isLowSurrogate(slug.charAt(end))) {
                end--;
            }
            slug = slug.substring(0, end).replaceAll("-+$", "");
        }
        return slug;
    }
}
