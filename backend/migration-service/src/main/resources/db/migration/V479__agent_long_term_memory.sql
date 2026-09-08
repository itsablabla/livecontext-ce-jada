-- ============================================================================
-- V476: Agent long-term memory - curated, workspace-scoped declarative facts.
--
-- WHAT THIS IS. Skills are PROCEDURAL memory ("how to do X"): instruction sets
-- a human authors once and assigns to an agent. This table is DECLARATIVE
-- memory ("what is true here"): atomic facts, preferences and corrections that
-- accumulate across conversations and runs, written by the agent as it works
-- or by the user by hand in the Memory tab. The two are deliberately separate
-- storage with separate lifecycles - see the project docs
-- point 12 ("skills = procedural, memory = declarative, never confuse them").
--
-- HOW IT REACHES THE MODEL. Two tiers, exactly like skills:
--   - `summary` is the index line and is injected into the system prompt on
--     every execution (cheap, one line per entry, hard-capped in chars).
--   - `content` is the body and is NOT injected; the agent fetches it with
--     memory(action='get', slug=...) when the index line looks relevant.
-- Entries flagged `pinned` inject their full body too, under a separate cap.
--
-- SCOPING. `organization_id` is the isolation boundary (post-V263 every
-- user-scoped table is NOT NULL on it, and the personal workspace is just the
-- org with is_personal = true). Switching workspace therefore switches the
-- whole memory set, which is the intended product behaviour. `tenant_id` is
-- the creating user, kept for attribution and the storage-usage rollup, NEVER
-- for isolation - reads go through ScopeGuard.isInStrictScope on org alone.
--
-- `agent_id` is the second axis and it is nullable:
--   NULL      -> workspace memory: every agent and every chat in the workspace
--                sees it. This is the default and the common case.
--   non-NULL  -> agent-private memory: only that agent sees it, so a fleet of
--                specialised agents does not drown in its siblings' facts.
-- ON DELETE CASCADE, deliberately not SET NULL: an agent-private fact must die
-- with its agent. Demoting it to workspace scope would silently widen its
-- audience, which is the one failure mode this column exists to prevent.
--
-- A per-USER scope inside a shared workspace (a member's private memory that
-- teammates cannot read) is NOT part of v1. It needs a predicate outside the
-- canonical ScopeGuard, and headless runs (schedules, webhooks) have an owner
-- but no "current user", so such entries would inject inconsistently. The
-- personal workspace already covers "my own private memory".
--
-- CAPS are enforced in Java at write time and expressed in CHARACTERS, not
-- tokens: a char count is model-independent, so switching model never
-- invalidates the limit (HERMES_MEMORY_STRATEGY.md §2.1 / §10 point 10).
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent.agent_memories (
    id                  UUID         PRIMARY KEY,
    tenant_id           VARCHAR(255) NOT NULL,
    organization_id     VARCHAR(255) NOT NULL,
    agent_id            UUID         NULL,

    -- Claude Code's four buckets. USER = who the person is and what they
    -- prefer; FEEDBACK = a correction they gave on how to work; PROJECT =
    -- durable facts about the work itself; REFERENCE = pointers to external
    -- resources. Rendered as the [type] tag on each index line.
    type                VARCHAR(16)  NOT NULL,

    -- The agent-facing handle. A readable slug rather than the UUID, because
    -- the agent reads and writes it in prompts all day and an opaque id there
    -- is pure token waste with no recall value. The UUID stays the DB key and
    -- the REST/UI handle.
    slug                VARCHAR(80)  NOT NULL,

    title               VARCHAR(120) NOT NULL,
    summary             VARCHAR(240) NOT NULL,
    content             TEXT         NOT NULL DEFAULT '',

    -- JSONB rather than TEXT[]: every other list-of-strings column in this
    -- schema (agent_tasks.label_ids, agent_tasks.blocked_by, ...) is a JSONB
    -- array mapped with @JdbcTypeCode(SqlTypes.JSON), and one storage idiom
    -- per codebase beats a marginally tidier type on a single table.
    tags                JSONB        NOT NULL DEFAULT '[]'::jsonb,
    pinned              BOOLEAN      NOT NULL DEFAULT FALSE,

    -- AGENT = written by an agent during a run, USER = written by a human in
    -- the Memory tab. Surfaced as a badge so a person auditing the list can
    -- tell at a glance what they authored from what an agent decided to keep.
    source              VARCHAR(8)   NOT NULL DEFAULT 'AGENT',
    created_by_agent_id UUID         NULL,

    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    recall_count        INTEGER      NOT NULL DEFAULT 0,
    last_recalled_at    TIMESTAMPTZ  NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_agent_memories_type
        CHECK (type IN ('USER', 'FEEDBACK', 'PROJECT', 'REFERENCE')),
    CONSTRAINT ck_agent_memories_source
        CHECK (source IN ('AGENT', 'USER')),
    CONSTRAINT fk_agent_memories_agent
        FOREIGN KEY (agent_id) REFERENCES agent.agents (id) ON DELETE CASCADE
);

-- Full-text search over the three human-readable fields. Generated + STORED so
-- the index is maintained by Postgres and no service can forget to refresh it.
-- 'simple' (not 'english') on purpose: the corpus is multilingual - this
-- product ships six locales - and an English stemmer mangles French and
-- Portuguese input while helping only one language.
ALTER TABLE agent.agent_memories
    ADD COLUMN IF NOT EXISTS search_vector TSVECTOR
    GENERATED ALWAYS AS (
        to_tsvector('simple',
            COALESCE(title, '') || ' ' || COALESCE(summary, '') || ' ' || COALESCE(content, ''))
    ) STORED;

-- Slug uniqueness is per scope, and the two scopes are disjoint, so it takes
-- two partial indexes rather than one composite: a NULL agent_id would make
-- every workspace row mutually distinct under a plain UNIQUE(org, agent, slug).
-- These are what make `save` an idempotent upsert - two agents recording the
-- same fact converge on one row instead of racing to duplicate it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_memories_workspace_slug
    ON agent.agent_memories (organization_id, slug)
    WHERE agent_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_memories_agent_slug
    ON agent.agent_memories (organization_id, agent_id, slug)
    WHERE agent_id IS NOT NULL;

-- The index-build hot path: every agent execution asks for "active entries in
-- this org visible to this agent, pinned first, newest first". organization_id
-- leads because it is the only equality the planner can range on: the agent
-- axis is (agent_id IS NULL OR agent_id = ?), an OR that no single range over
-- the trailing columns satisfies, so expect a scan of one workspace's rows plus
-- a filter rather than a straight index walk. That is the right trade at this
-- size (a workspace holds at most maxWorkspaceEntries + per-agent caps, i.e.
-- hundreds), and the remaining columns still help the sort.
CREATE INDEX IF NOT EXISTS idx_agent_memories_scope
    ON agent.agent_memories (organization_id, agent_id, is_active, pinned, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_memories_search
    ON agent.agent_memories USING GIN (search_vector);

COMMENT ON TABLE agent.agent_memories IS
    'Curated declarative long-term memory. Workspace-isolated on organization_id; agent_id NULL = shared by the whole workspace, non-NULL = private to that agent.';
COMMENT ON COLUMN agent.agent_memories.summary IS
    'The one-line index entry injected into every agent system prompt in scope. Keep it short - it is paid for on every execution.';
COMMENT ON COLUMN agent.agent_memories.content IS
    'The body. NOT injected into the prompt; fetched on demand via memory(action=''get'').';
COMMENT ON COLUMN agent.agent_memories.tenant_id IS
    'Creating user. Attribution and storage rollup only - isolation is organization_id (ScopeGuard.isInStrictScope).';
