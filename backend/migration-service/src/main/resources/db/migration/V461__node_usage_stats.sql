-- ============================================================================
-- V461: platform-wide "how often is this node actually run" ledger.
--
-- WHAT IT ANSWERS. The add-node palette ranks integrations by real usage instead
-- of alphabetically. Nothing today records that a node RAN: workflow_step_data
-- holds one row per step per run and is pruned, so deriving a ranking from it
-- would mean scanning run history on every palette open. These tables are the
-- pre-aggregated answer: one row per key, read with an index scan.
--
-- SCOPE. Platform-wide on purpose - no tenant column. The ranking exists to tell
-- a builder "this is what the platform uses", which is only meaningful across
-- tenants, and a per-tenant breakdown would carry cross-tenant inference risk
-- for no gain to that question. It also means no row here belongs to anyone: the
-- counters are sums with no subject, which is why they are readable by any
-- signed-in builder while the raw numbers are never served (only the ORDER).
--
-- THREE TABLES, TWO OWNERS. orchestrator owns "which node types run" because it
-- is the only service that sees a node launch. catalog owns the two tool tables
-- because resolving a tool identifier to its API is a catalog fact
-- (`tool_slug` is DERIVED at import - see scripts/api-migrations/SCHEMA.md - so
-- an api slug can never be recovered from a tool slug by string surgery).
-- The orchestrator pushes tool deltas over HTTP; it never writes catalog.*.
--
-- BOTH LEVELS ARE STORED, NOT DERIVED. catalog.api_usage_stats could be a
-- GROUP BY over catalog.tool_usage_stats, and is not: the palette pages through
-- APIs ordered by usage, and re-aggregating tens of thousands of endpoint rows
-- on every page fetch is exactly the cost this ledger exists to avoid. Both are
-- written by the same statement pair, so they cannot drift apart.
--
-- NOT A BILLING RECORD. These counters are best-effort: they are accumulated in
-- memory and flushed on a timer, so a pod killed mid-interval loses its last
-- window. That is acceptable for an ordering and would not be for anything that
-- charges money - do not grow a billing feature on top of this table.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- orchestrator: one row per node type ("agent", "core:decision", "mcp", ...).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orchestrator.node_usage_stats (
    node_key   VARCHAR(160) NOT NULL PRIMARY KEY,
    run_count  BIGINT       NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The only read: "give me the keys, most-run first".
CREATE INDEX IF NOT EXISTS idx_node_usage_stats_rank
    ON orchestrator.node_usage_stats (run_count DESC, node_key);

-- ---------------------------------------------------------------------------
-- catalog: one row per endpoint, and one per integration.
-- ---------------------------------------------------------------------------
-- Keyed by tool_slug rather than api_tools.id because a catalog re-import is a
-- DELETE-then-reimport (see AGENTS.md): the UUID of an endpoint does not survive
-- it, while the slug is exactly what workflows pin and what the importer keeps
-- stable. No FK for the same reason - a re-import must not cascade away the
-- history, the way it once cascaded away user credentials.
CREATE TABLE IF NOT EXISTS catalog.tool_usage_stats (
    tool_slug  VARCHAR(255) NOT NULL PRIMARY KEY,
    api_slug   VARCHAR(255) NOT NULL,
    run_count  BIGINT       NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tool_usage_stats_api
    ON catalog.tool_usage_stats (api_slug);

CREATE INDEX IF NOT EXISTS idx_tool_usage_stats_rank
    ON catalog.tool_usage_stats (run_count DESC, tool_slug);

CREATE TABLE IF NOT EXISTS catalog.api_usage_stats (
    api_slug   VARCHAR(255) NOT NULL PRIMARY KEY,
    run_count  BIGINT       NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Drives the palette's paged ranking: ORDER BY run_count DESC, api_slug.
CREATE INDEX IF NOT EXISTS idx_api_usage_stats_rank
    ON catalog.api_usage_stats (run_count DESC, api_slug);
