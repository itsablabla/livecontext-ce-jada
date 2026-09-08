-- ============================================================================
-- V465: the generate node moves from the control-flow family to AI.
--
-- It was filed under `core` because that is where nodes with a generic params
-- map lived, not because it belongs there: nothing about it is control flow.
-- What it does is call a model and hand back what the model produced, which is
-- what every other node in the AI family does. It is now stored beside them
-- (agents[] in the plan), addressed like them (`agent:<label>`), and listed
-- with them wherever a category is offered.
--
-- Two columns carry that, and BOTH matter to an agent:
--   * `category` is how the node is FILED for a reader: 'ai' is what the AI
--     family uses (agent, classify and guardrail all carry it). Left at 'core'
--     the node reads as control flow, which is the whole thing being corrected.
--   * `variable_prefix` does two jobs. It is what the node LIBRARY groups by,
--     so it decides whether generate shows up under
--     workflow(action='help', topics=['ai']) or in the control-flow list; and
--     it says how the node's output is ADDRESSED. Left at 'core' it would
--     document {{core:<label>.output.file}} for a node the engine registers as
--     agent:<label>, and every reference an agent wrote from this row would
--     resolve to nothing - silently, because an unresolved template is an empty
--     string, not an error.
--
-- Deliberately NOT migrating existing plans. There is no compatibility path
-- here by decision: a plan holding the node under cores[] no longer builds it.
--
-- Targeted UPDATEs of named paths: every other column of this row, and every
-- other row, is left exactly as it is.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET category = 'ai',
    variable_prefix = 'agent',
    updated_at = NOW()
WHERE type = 'generate';

-- browser_agent was the one AI row filed as 'agent' while its three siblings
-- carry 'ai'. Corrected in the same pass rather than left as the new odd one
-- out: a family that answers to two different names for itself is how a reader
-- concludes the two mean different things. Nothing is keyed on this value
-- (the library groups by variable_prefix, the plan gate by type), so this is a
-- naming correction, not a behaviour change.
UPDATE node_type_documentation
SET category = 'ai',
    updated_at = NOW()
WHERE type = 'browser_agent'
  AND category <> 'ai';

-- The addressing examples that live INSIDE the jsonb text. A prefix column
-- says what the key is; these sentences are what an agent actually copies.
UPDATE node_type_documentation
SET outputs = jsonb_set(
        outputs,
        '{file,description}',
        to_jsonb(
            'The generated asset, stored so it outlives the provider''s own link. Reference via '
            || '{{agent:<label>.output.file}} and map the WHOLE object into a downstream file param, '
            || 'never .path or a URL.'
        ),
        false
    ),
    updated_at = NOW()
WHERE type = 'generate'
  AND outputs -> 'file' ? 'description';

UPDATE node_type_documentation
SET parameters = jsonb_set(
        parameters,
        '{input_image,description}',
        to_jsonb(
            'A reference image or first frame: the WHOLE FileRef output of an upstream node as a '
            || 'whole-value template (e.g. {{core:download.output.file}} for a core node, '
            || '{{agent:make_cover.output.file}} for another generate node), never .path and never a URL.'
        ),
        false
    ),
    updated_at = NOW()
WHERE type = 'generate'
  AND parameters -> 'input_image' ? 'description';

-- One concept line named the old prefix as the way to chain into core:media.
UPDATE node_type_documentation
SET concepts = (
        SELECT jsonb_agg(
            CASE
                WHEN entry #>> '{}' LIKE '%core:media%'
                THEN to_jsonb(
                    'Chain into core:media to edit what was generated: generate a clip, generate a '
                    || 'voice over, then mux_audio the two together. The generated asset is addressed '
                    || 'as {{agent:<label>.output.file}} - generate is an AI node, not a core one, so a '
                    || '{{core:...}} reference to it resolves to nothing.'
                )
                ELSE entry
            END
            ORDER BY ordinality
        )
        FROM jsonb_array_elements(concepts) WITH ORDINALITY AS t(entry, ordinality)
    ),
    updated_at = NOW()
WHERE type = 'generate'
  AND jsonb_typeof(concepts) = 'array'
  AND concepts::text LIKE '%core:media%';

-- The last place the old key survives is a COLUMN COMMENT, written by V428 and
-- therefore unreachable by editing that file: it is already applied everywhere.
-- It is what a reader inspecting the catalog schema is told the descriptor is
-- FOR, and it names a node id that no longer exists. Re-issued here, guarded on
-- the table being present so the statement is a no-op wherever this migration
-- runs against a partial schema instead of an error.
DO $$
BEGIN
    IF to_regclass('catalog.api_tools') IS NOT NULL THEN
        COMMENT ON COLUMN catalog.api_tools.generation_spec IS
            'Declarative generation descriptor imported from the `generation` block of the api-migrations JSON. NULL = ordinary endpoint. Non-null = this endpoint backs one or more generation models addressable by the unified `generation` tool and the agent:generate node. Shape: {kind, submitTool, pollTool, assetPath, paramMap, models[]}.';
    END IF;
END $$;

-- The media row carries the same concat example as the generate help, and it
-- names the two clips as core: nodes. That example was written when a generate
-- node WAS a core; an agent that copies it for one now writes a reference that
-- resolves to an empty string. Two agent-facing copies of one example must not
-- disagree on the prefix, so the row follows the help.
UPDATE node_type_documentation
SET examples = to_jsonb(
        replace(replace(examples::text,
                '{{core:intro.output.file}}', '{{agent:intro.output.file}}'),
                '{{core:outro.output.file}}', '{{agent:outro.output.file}}')::jsonb
    ),
    updated_at = NOW()
WHERE type = 'media'
  AND jsonb_typeof(examples) = 'array'
  AND examples::text LIKE '%{{core:intro.output.file}}%';