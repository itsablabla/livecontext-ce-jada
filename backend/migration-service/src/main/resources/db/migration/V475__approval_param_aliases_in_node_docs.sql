-- User Approval node: record the accepted parameter ALIASES in the node's own documentation.
--
-- WHY: the approval node's builder help and its documented schema advertised DIFFERENT
-- spellings of the same parameters. WorkflowBuilderPrompts says timeoutMs / requiredApprovals /
-- approverRoles, this table said timeout_ms / required_approvals / approver_roles, and
-- DecisionNodeCreator.executeAddApproval has always read both. Until NodeParamsValidator gained
-- an "approval" alias entry, add_node rejected the camelCase half with "Unknown parameter" for a
-- value the creator would have honoured (found live 2026-09-05 on timeoutMs).
--
-- Both spellings work now. This migration makes the documentation SAY so, following the
-- precedent V391 set for contextTemplate ("Alias: context_template."), so an agent reading
-- workflow(action='help', topics=['approval']) is not left guessing which half of the platform
-- to believe.
--
-- Descriptions only: no key is added, renamed or removed, so nothing that reads these
-- parameters changes behaviour. Each write is a whole-string replacement rather than an append,
-- so re-running the migration cannot stack the sentence twice. Schema-qualified because the
-- search_path is reset between migrations.
SET search_path TO orchestrator;

UPDATE orchestrator.node_type_documentation
SET parameters = jsonb_set(
        jsonb_set(
            jsonb_set(
                jsonb_set(
                    parameters,
                    '{timeout_ms,description}',
                    '"Timeout in milliseconds before the timeout port fires. Default: 86400000 (24 hours). Aliases: timeoutMs, timeout."'::jsonb,
                    true
                ),
                '{approver_roles,description}',
                '"List of roles that can approve (e.g., [''manager'', ''admin'']). Empty = any user. Aliases: approverRoles, roles."'::jsonb,
                true
            ),
            '{required_approvals,description}',
            '"Number of approvals needed (for multi-level approval). Default: 1. Alias: requiredApprovals."'::jsonb,
            true
        ),
        '{continuationMode,description}',
        '"Split-context continuation. Only matters when this approval runs inside a split (one approval per item). ''all_items'' (default): downstream steps start once, after every item''s approval is decided. ''per_item'': each approved/rejected item continues its own downstream chain immediately (use it to process each approved item without waiting for the rest of the batch); the first cross-item node (merge, aggregate, loop, fork, nested split) still waits for all items. Outside a split the setting has no effect. Unknown values fall back to ''all_items''. Alias: continuation_mode."'::jsonb,
        true
    ),
    updated_at = NOW()
-- Guarded like V391: jsonb_set creates a leaf only when its PARENT exists, so on a row missing
-- one of these keys the statement would be a silent no-op with no error and no signal. Requiring
-- all four (and a non-null parameters, which jsonb_set would otherwise turn into NULL) means the
-- update either applies in full or touches nothing.
WHERE type = 'approval'
  AND parameters IS NOT NULL
  AND parameters ? 'timeout_ms'
  AND parameters ? 'approver_roles'
  AND parameters ? 'required_approvals'
  AND parameters ? 'continuationMode';
