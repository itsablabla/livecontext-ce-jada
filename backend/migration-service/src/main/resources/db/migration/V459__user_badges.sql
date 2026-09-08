-- V459: Unlockable user badges (trophies).
--
-- One row per (tenant, badge) the user has ALREADY unlocked. The badge
-- CATALOG itself is not stored here: definitions (code, family, tier,
-- threshold, metric) live in `BadgeCatalog.java` so adding a badge is a code
-- change, not a data migration, and the same list is served to the frontend
-- via GET /api/badges/catalog. This table only records the unlock EVENT.
--
-- Unlocks are permanent: `BadgeService` never deletes a row when the
-- underlying metric later drops (deleting a workflow must not take a trophy
-- away). `progress_value` freezes the metric value at unlock time so the UI
-- can show "unlocked at 1,043 runs" without recomputing history.

CREATE TABLE IF NOT EXISTS orchestrator.user_badges (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      VARCHAR(255) NOT NULL,
    badge_code     VARCHAR(64)  NOT NULL,
    progress_value BIGINT       NOT NULL DEFAULT 0,
    unlocked_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_badges_tenant_code UNIQUE (tenant_id, badge_code)
);

COMMENT ON TABLE orchestrator.user_badges IS
    'Unlocked trophies per user. Append-only in practice: an unlock is never revoked.';
COMMENT ON COLUMN orchestrator.user_badges.badge_code IS
    'Stable code from BadgeCatalog (e.g. ''operator_1000''). Unknown codes are ignored '
    'on read so removing a badge from the catalog cannot break the page.';
COMMENT ON COLUMN orchestrator.user_badges.progress_value IS
    'Metric value observed at unlock time - displayed as-is, never recomputed.';

-- The badge grid reads every unlocked row for one tenant, newest first (the
-- "recently unlocked" strip on the profile). The UNIQUE constraint above
-- already covers the (tenant_id, badge_code) point lookup used by the
-- evaluator's ON CONFLICT.
CREATE INDEX IF NOT EXISTS idx_user_badges_tenant_unlocked
    ON orchestrator.user_badges (tenant_id, unlocked_at DESC);

-- The bell needs BADGE as a subject type: unlocking one emits a
-- BADGE_UNLOCKED notification whose subject_id is a deterministic UUID
-- derived from the badge code (so re-evaluation collapses onto the same row).
ALTER TABLE orchestrator.notifications
    DROP CONSTRAINT IF EXISTS chk_notif_subject_type_v1;

ALTER TABLE orchestrator.notifications
    ADD CONSTRAINT chk_notif_subject_type_v1
        CHECK (subject_type IN (
            'WORKFLOW',
            'APPLICATION',
            'AGENT_TASK',
            'CREDENTIAL',
            'TRIGGER',
            'ORG_INVITATION',
            'BADGE'
        ));

COMMENT ON CONSTRAINT chk_notif_subject_type_v1 ON orchestrator.notifications IS
    'V459 relaxed: admits BADGE for trophy unlock notifications.';
