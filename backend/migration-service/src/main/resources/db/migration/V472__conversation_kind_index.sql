-- V472: the index the kind-filtered conversation listing reads through.
--
-- Split from V470 (which adds the column) because this one must run OUTSIDE Flyway's transaction
-- wrapper, and a script that cannot be wrapped should not also carry DDL that wants to be: if the
-- index build failed, a combined script would leave the column added and the migration recorded as
-- failed, with nothing to roll back.
--
-- Columns follow the predicate of the read this exists for, the strict-org sidebar listing of one
-- kind: organization_id = ?, active = true, kind = ?, ORDER BY updated_at DESC. Leading with
-- organization_id and closing on updated_at lets that read seek straight to its page instead of
-- sorting the workspace.
--
-- PARTIAL, on the same predicate as idx_conversations_org_user_updated (V211): a soft-deleted or
-- personal-scope row can never satisfy this listing, so indexing it only pays for it. That matters
-- more here than on most tables because updated_at moves on EVERY message: each turn of every chat
-- rewrites this index entry, and the rows excluded below are pure write cost.
--
-- Two deliberate limits of that choice:
--   * The include-inactive variant of the listing (an explicit opt-in, not the sidebar) is not
--     covered and falls back to the org indexes. It is the rare path; paying on every message to
--     serve it would be the wrong trade.
--   * The personal-scope listings, which filter on user_id rather than organization_id
--     (findByUserIdAndActiveTrueOrderByUpdatedAtDesc and its siblings), are served by their own
--     indexes and are untouched by this one.
--
-- Lock posture: CONCURRENTLY takes SHARE UPDATE EXCLUSIVE, so chat carries on during the build. A
-- plain CREATE INDEX would take ACCESS EXCLUSIVE and block every read and write to conversations
-- for its duration.
--
-- On crash: PG marks the index INVALID. Recovery: drop it and re-run this migration (Flyway records
-- version 472, so use flyway:repair first, then re-trigger with a no-op comment change).

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_conversations_org_kind_updated
    ON conversation.conversations (organization_id, kind, updated_at DESC)
    WHERE organization_id IS NOT NULL AND active = TRUE;
