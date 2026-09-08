-- ============================================================================
-- V466: workspace / account purge becomes an OUTBOX + per-service followers.
--
-- Until now auth-service's WorkspaceDataPurger deleted the org-scoped rows of
-- nine schemas itself, in native cross-schema SQL, from one JVM. That was the
-- single piece of code pinning every schema to the same Postgres: as long as
-- auth reaches into datasource.* directly, datasource.* cannot move to its own
-- database (the scaling path for user tables and vectors), and it was also the
-- only class the new CI guard (scripts/ci/check-cross-schema-sql.py) had to
-- allow-list.
--
-- From here on:
--   * auth writes ONE row per purge into auth.purge_log, in the same transaction
--     as its own deletes (subject ORG = a workspace id, USER = an account id).
--   * every other service runs a follower (auth-client PurgeFollower) that pulls
--     new rows over HTTP, deletes ITS OWN rows for each subject, and advances a
--     cursor kept in ITS OWN schema. Idempotent, so a crash mid-batch simply
--     replays; no lock needed, so no ShedLock table for services that lack one.
--
-- The backfill below replays every workspace already purged: their rows were
-- deleted by the old purger, so the followers' first pass is a no-op for them,
-- except for anything that purger's per-statement savepoints silently skipped.
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth.purge_log (
    seq           BIGSERIAL PRIMARY KEY,
    subject_type  VARCHAR(8)  NOT NULL CHECK (subject_type IN ('ORG', 'USER')),
    subject_id    VARCHAR(64) NOT NULL,
    source        VARCHAR(32) NOT NULL,
    purged_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE auth.purge_log IS
    'Outbox of purge decisions. Followers in every other service consume it by seq and delete their own rows; a row here is a promise that the data is gone everywhere within minutes.';

INSERT INTO auth.purge_log (subject_type, subject_id, source, purged_at)
SELECT 'ORG', id::text, 'backfill-v466', purged_at
FROM auth.organization
WHERE purged_at IS NOT NULL
ORDER BY purged_at;

-- One cursor row per consuming schema. id is pinned to 1 so the row is a singleton.
CREATE TABLE IF NOT EXISTS orchestrator.purge_cursor  (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS agent.purge_cursor         (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS interface.purge_cursor     (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS datasource.purge_cursor    (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS "trigger".purge_cursor     (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS storage.purge_cursor       (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS publication.purge_cursor   (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS conversation.purge_cursor  (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

INSERT INTO orchestrator.purge_cursor  (id) VALUES (1) ON CONFLICT DO NOTHING;
INSERT INTO agent.purge_cursor         (id) VALUES (1) ON CONFLICT DO NOTHING;
INSERT INTO interface.purge_cursor     (id) VALUES (1) ON CONFLICT DO NOTHING;
INSERT INTO datasource.purge_cursor    (id) VALUES (1) ON CONFLICT DO NOTHING;
INSERT INTO "trigger".purge_cursor     (id) VALUES (1) ON CONFLICT DO NOTHING;
INSERT INTO storage.purge_cursor       (id) VALUES (1) ON CONFLICT DO NOTHING;
INSERT INTO publication.purge_cursor   (id) VALUES (1) ON CONFLICT DO NOTHING;
INSERT INTO conversation.purge_cursor  (id) VALUES (1) ON CONFLICT DO NOTHING;
