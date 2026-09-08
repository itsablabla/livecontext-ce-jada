-- ============================================================================
-- V469: the Browser Agent node becomes a PRO capability on managed cloud.
--
-- It was available on every plan, including FREE, because auth.plan_feature_requirement
-- holds only the exceptions and nobody had written this one down. NodePlanGate's own javadoc
-- used `node:browser_agent` as its example of a gated node, which made it easy to believe the
-- row existed; it did not.
--
-- Why it belongs behind a paid plan, next to vector search (V467): a browser agent drives a real
-- headless browser on the operator's sidecar, one session at a time, for as long as the task
-- takes. Unlike an API call it occupies a scarce, stateful, per-run resource, and unlike a
-- workflow node it can be pointed at anything on the internet. Free-tier abuse of it costs
-- capacity every other tenant is waiting for.
--
-- The key is `node:<documented type>`, and the documented type is `browser_agent`
-- (V139__browser_agent_node_docs.sql). The builder derives the same key from its palette id, so
-- the padlock and the tooltip naming the plan appear with no frontend change.
--
-- Enforcement already exists and is unchanged: NodePlanGate fails the node at run time with
-- error_code PLAN_UPGRADE_REQUIRED and the plan named, WorkflowBuilderProvider refuses to add it
-- from the agent, and the palette marks it. Self-hosted is never gated (PlanTier reports CE).
--
-- An admin can move or clear this in Settings > Node types without a deploy; setting it to FREE
-- deletes the row and hands the node back to everyone.
--
-- Note this is orthogonal to whether the deployment HAS a browser sidecar at all, which the
-- feature-capabilities endpoint answers separately. Both must be true to run the node.
-- ============================================================================

INSERT INTO auth.plan_feature_requirement (feature_key, min_plan, label, updated_by)
VALUES ('node:browser_agent', 'PRO', 'Browser Agent node', 'seed:V469')
ON CONFLICT (feature_key) DO NOTHING;
