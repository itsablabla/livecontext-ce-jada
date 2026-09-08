-- Workflow spending cap: from "lifetime of one run" to "per calendar period,
-- every run except a builder test fire".
--
-- V411 introduced orchestrator.workflows.budget_credits and compared it against
-- workflow_runs.cost_credits, which is the run's total across ALL its epochs.
-- A pinned workflow keeps ONE production run forever and accumulates epochs on
-- it, so that comparison turned the budget into a lifetime cap: the workflow
-- fired until the cap was reached and then stopped for good, silently. That is
-- the failure mode the cap exists to prevent, one level up.
--
-- This migration keeps budget_credits as THE amount (so every existing DTO,
-- endpoint and UI field keeps working) and adds the period bookkeeping beside
-- it. From here on:
--
--   budget_credits           the ceiling, in credits. NULL or <= 0 = no cap.
--   budget_period_mode       'monthly' (default), 'weekly' or 'cumulative'.
--                            'cumulative' never resets and reproduces the old
--                            lifetime behaviour for anyone who wants it.
--   budget_period_spent      credits consumed by the governed fires in the
--                            period currently open. Reset to the new fire's cost
--                            when a period rolls over (done inside the same
--                            increment statement, so a rollover can never lose a
--                            write).
--   budget_period_started_at start of the period budget_period_spent belongs to,
--                            truncated to the mode's unit in UTC. NULL means no
--                            cost has been recorded yet.
--
-- WHICH runs feed budget_period_spent, and are refused by it: every run EXCEPT
-- a builder test fire. The rule lives in one place in Java
-- (WorkflowBudgetState.appliesToRun) and needs BOTH signals, so do not
-- reconstruct it from one of them here or in a query:
--
--   * The FK workflows.production_run_id proves a run IS production, and wins.
--   * The run metadata flag __editorRun__ marks a builder test fire, and only
--     excuses a run the FK has not claimed. It cannot be the only test:
--     pinning promotes an editor run to production without stripping the flag,
--     so a promoted run carries it forever.
--
-- Using BOTH is not fixing a live hole - a non-editor run of an unpinned
-- workflow is refused earlier by the trigger chokepoint, verified on a live
-- stack. It is here so that ONE predicate decides both whose spend is counted
-- and whose run is refused; they used to disagree.

ALTER TABLE orchestrator.workflows
    ADD COLUMN IF NOT EXISTS budget_period_mode VARCHAR(16) NOT NULL DEFAULT 'monthly';

ALTER TABLE orchestrator.workflows
    ADD COLUMN IF NOT EXISTS budget_period_spent NUMERIC(15,4) NOT NULL DEFAULT 0;

ALTER TABLE orchestrator.workflows
    ADD COLUMN IF NOT EXISTS budget_period_started_at TIMESTAMPTZ;

-- Guard the mode against a typo from a hand-written UPDATE: an unknown value
-- would silently fall back to "never reset" in the Java rollover rule.
ALTER TABLE orchestrator.workflows
    DROP CONSTRAINT IF EXISTS workflows_budget_period_mode_check;

ALTER TABLE orchestrator.workflows
    ADD CONSTRAINT workflows_budget_period_mode_check
    CHECK (budget_period_mode IN ('monthly', 'weekly', 'cumulative'));

-- Existing workflows that already carry a cap were living under the lifetime
-- rule. Leaving them on the default 'monthly' with a NULL period start means
-- their first production cost after this deploy opens a fresh period, so the
-- cap starts counting from now instead of from everything they ever spent.
-- That is the intended migration: nobody wakes up blocked by a cap they hit
-- months ago.
COMMENT ON COLUMN orchestrator.workflows.budget_credits IS
    'Spending ceiling in credits per budget_period_mode, counting agent spend by every run except a builder test fire. NULL or <= 0 = no cap.';
COMMENT ON COLUMN orchestrator.workflows.budget_period_spent IS
    'Agent credits spent in the currently open period by every run except a builder test fire (see WorkflowBudgetState.appliesToRun). Written only by the native statements in WorkflowRepository: the increment on each settle, and the reset when the cap or its cadence changes. Never by an entity save (the column is mapped updatable=false).';
