-- ============================================================================
-- V467: vector columns and similarity search become a PAID feature on managed
-- cloud, instead of being refused outright.
--
-- Until now, embeddings were self-hosted-only: datasource-service computed one
-- boolean from the deployment's edition and refused every vector column and
-- every similarity search on managed cloud. The reason was real and has not
-- gone away - an unbounded RAG corpus on the shared Postgres competes for
-- cache, CPU and disk with every other schema, including the execution
-- engine's hot path - but it is a reason to PRICE the feature, not to withhold
-- it. This row is what prices it.
--
-- A FOURTH key namespace, and the first one that is not a thing in a catalogue:
--   feature:<capability>   a product capability that is not a node type, a
--                          catalog API or one of its endpoints.
-- Vectors are exactly that. They are not a node: gating node:find_rows would
-- hold back ordinary row lookups, and gating node:create_column would hold back
-- every column type. The capability spans a column type, a query operator, an
-- index and a marketplace listing flag, so it needs a key of its own.
--
-- Enforcement lives in datasource-service's VectorFeatureGate, which asks
-- PlanFeatureGate about the tenant that OWNS the table, not the caller: a
-- table's capabilities belong to the workspace that owns it, so a workspace on
-- a plan that includes vectors keeps working for every member and every
-- workflow run. Self-hosted is never gated (PlanTier reports CE, which clears
-- every bar, and the gate short-circuits on edition as well).
--
-- Setting this row back to FREE in the admin screen DELETES it and hands
-- vectors to everyone; raising it to TEAM moves the bar without a deploy. That
-- is the point of putting it here rather than in code.
-- ============================================================================

INSERT INTO auth.plan_feature_requirement (feature_key, min_plan, label, updated_by)
VALUES ('feature:vector_search', 'PRO',
        'Vector columns and similarity search (RAG)', 'seed:V467')
ON CONFLICT (feature_key) DO NOTHING;

COMMENT ON TABLE auth.plan_feature_requirement IS
    'Minimum subscription plan required to use a workflow node type, a catalog endpoint, a whole catalog API, or a product capability (feature:*). No row = available on every plan.';
