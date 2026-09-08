-- ============================================================================
-- V468: publications flagged un-installable ONLY because they use embeddings
-- become installable again on managed cloud.
--
-- V420 introduced two feature codes on publication.workflow_publications and
-- collapsed them into one boolean: ce_exclusive = "the snapshot uses CLI_AGENT
-- or VECTOR_SEARCH". That was right while both meant "managed cloud cannot run
-- this at all". V467 changed the second one: vector search now runs on cloud
-- from a plan, so an app that uses embeddings is priced, not impossible.
--
-- The code half of that change (CeExclusiveFeatureDetector.BLOCKING_FEATURES)
-- only affects rows written from now on: ce_exclusive is stamped at publish and
-- at update, so every app published while vectors were self-hosted-only would
-- keep its stale flag until its author happened to re-publish it. On cloud that
-- means a listing that stays badged "Community Edition exclusive" and refuses
-- to install, for a capability the visitor's plan may well include. This
-- migration repairs those rows once.
--
-- Scope, deliberately narrow:
--   * rows whose feature list contains VECTOR_SEARCH and NOT CLI_AGENT lose the
--     boolean;
--   * rows containing CLI_AGENT are untouched, at any combination. A local CLI
--     agent still has no host on managed cloud at any price, so those apps stay
--     un-installable and stay badged.
--
-- ce_exclusive_features is NOT cleared. It is what explains the badge and the
-- refusal to a person and to an agent, and the vector fact remains true and
-- useful: the acquire path reads it to decide whether the workspace's plan has
-- to include vector search. Clearing it would make the app installable by
-- anyone and silently strip its embedding columns on clone, which is the exact
-- failure this pair of migrations exists to avoid.
-- ============================================================================

UPDATE publication.workflow_publications
SET ce_exclusive = FALSE
WHERE ce_exclusive = TRUE
  AND ce_exclusive_features @> '["VECTOR_SEARCH"]'::jsonb
  AND NOT (ce_exclusive_features @> '["CLI_AGENT"]'::jsonb);

COMMENT ON COLUMN publication.workflow_publications.ce_exclusive IS
    'TRUE when the snapshot uses a feature managed cloud cannot run at any price (today: a local CLI agent). Vector search is NOT one of these since V467/V468: it is plan-gated at acquire instead. Derived at publish and update by CeExclusiveFeatureDetector.applyTo.';

COMMENT ON COLUMN publication.workflow_publications.ce_exclusive_features IS
    'Every special capability detected in the snapshot (CLI_AGENT, VECTOR_SEARCH), whether or not it blocks. Explains the badge and the refusal, and drives the plan check on VECTOR_SEARCH at acquire time.';
