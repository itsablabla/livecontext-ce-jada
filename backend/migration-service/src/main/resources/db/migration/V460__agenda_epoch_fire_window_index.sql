-- Agenda: index the "which trigger fires happened in this window" lookup.
--
-- The calendar's past days are drawn from workflow_epochs: one EPOCH_HEADER row per
-- trigger fire, which is the only per-fire record that exists (a schedule row remembers
-- only its LAST fire and a total count). Opening a month asks for every header belonging
-- to the WORKSPACE whose started_at falls in that month
-- (WorkflowEpochRepository.findWorkspaceFiresBetween, which joins epochs -> runs ->
-- workflows and filters on the workflow's organization_id).
--
-- Leading column is run_id, NOT started_at. workflow_epochs carries no tenant or
-- organization column, so the only selective predicate available to the index is the run.
-- An index leading with started_at would make a five-workflow workspace scan every
-- organization's headers for that month and discard the rest after the join - and LIMIT
-- cannot short-circuit it, because the discriminating predicate is applied post-join. With
-- (run_id, started_at) the planner drives from the org's workflows (indexed on
-- organization_id) through their runs (indexed on workflow_id) and probes only that
-- workspace's headers, at the cost of sorting a bounded result.
--
-- The partial predicate keeps the index small and the hot path untouched: the counter rows
-- (entry_type EDGE/NODE/...) are the bulk of the table and are never the subject of this
-- question.
--
-- CONCURRENTLY is deliberately NOT used: Flyway runs each migration in a transaction and
-- CREATE INDEX CONCURRENTLY cannot run inside one. The partial predicate keeps the build
-- short.
CREATE INDEX IF NOT EXISTS idx_we_fire_window
    ON workflow_epochs (run_id, started_at)
    WHERE entry_type = 'EPOCH_HEADER';
