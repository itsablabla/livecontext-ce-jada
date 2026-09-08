-- ============================================================================
-- V458: publishing endpoints unlock at STARTER, not PRO.
--
-- The bar is "has a paid subscription", not "is on the second paid tier".
-- V457 seeded these 51 endpoints at PRO, which put publishing behind a plan two
-- steps up from the first paid one and made the Starter card promise less than
-- it should.
--
-- Scoped to the rows V457 wrote and still holds at PRO, so an admin who has
-- since moved an endpoint deliberately (to TEAM, or down to FREE, which deletes
-- the row) keeps their decision. Re-running is a no-op.
-- ============================================================================

UPDATE auth.plan_feature_requirement
SET min_plan = 'STARTER',
    updated_at = NOW(),
    updated_by = 'seed:V458'
WHERE updated_by = 'seed:V457'
  AND min_plan = 'PRO';
