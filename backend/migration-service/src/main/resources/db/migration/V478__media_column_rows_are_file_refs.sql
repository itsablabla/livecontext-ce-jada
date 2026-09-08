-- A 'file' / 'image' column now reads back as the file OBJECT, so say so where the agent looks.
--
-- The value was always a file reference, but the JSONB write flattened it to text, so every reader
-- had to parse it - which is why workflows kept a hand-written reference in a 'text' column instead
-- of using the column type built for it. The read path re-inflates it now; these docs are what stops
-- an agent from reaching for the old pattern out of habit, and what warns it about the two things
-- that used to work and no longer do.
--
-- The output descriptions below keep the sentence V20 and V253 wrote and append the media contract
-- to it, so nothing an earlier migration said is lost. They no longer match the NodeSpec wording
-- word for word (nothing enforces that parity today); what has to agree is the CONTRACT: map the
-- whole cell, path and url are each present only when the file has one.
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  outputs = COALESCE(outputs, '{}'::jsonb) || '{
    "items": {"type": "array", "description": "All found rows (after limit applied). Use a Split node to iterate them. A cell from a file or image column is the file OBJECT {_type:''file'', id, path, url, name, mimeType, size} - map the WHOLE cell into a parameter that takes a file, do not parse it and do not read one field out of it. path and url are each present only when the file has one."}
  }'::jsonb,
  concepts = (
    SELECT COALESCE(jsonb_agg(concept), '[]'::jsonb)
    FROM jsonb_array_elements(COALESCE(node_type_documentation.concepts, '[]'::jsonb)) AS concept
    WHERE concept #>> '{}' NOT LIKE 'A file/image column comes back%'
      AND concept #>> '{}' NOT LIKE 'Do NOT store a file reference as JSON text%'
      AND concept #>> '{}' NOT LIKE 'A node that reads the file%'
      AND concept #>> '{}' NOT LIKE 'CHANGED:%'
  ) || '["A file/image column comes back as a usable file reference: map the whole cell - {{table:<label>.output.items[0].<column>}}, or {{item.<column>}} once a Split node is iterating the rows - into a public_link node, a media node, or any tool parameter that takes a file", "Do NOT store a file reference as JSON text in a text column and parse it in a code node - that older pattern still runs, so it is easy to copy from an existing workflow, but a file/image column removes the parse step", "A node that reads the file''s bytes (public_link, media, extract_from_file) needs the cell''s path, and a cell carries one only when the file it names has a storage path - a link to somewhere else never does, and neither does a file this workspace knows only by id - so check for path when the workflow depends on the bytes", "CHANGED: these cells used to arrive as a JSON string. In an existing workflow, delete any code node that parses one (parsing an object fails), stop passing one as a where value (the filter compares the stored text, so it now matches nothing) and stop copying one into a text column via set (a cell carrying a url or an id lands there as a short readable summary, not a reusable reference). An extract_from_file node pointed at a cell whose file has no stored path used to complete by extracting the references own text as the document, and now fails instead"]'::jsonb,
  updated_at = NOW()
WHERE type = 'find_rows';

UPDATE node_type_documentation
SET
  outputs = COALESCE(outputs, '{}'::jsonb) || '{
    "rows": {"type": "array", "description": "Retrieved rows. A cell from a file or image column is the file OBJECT {_type:''file'', id, path, url, name, mimeType, size} - map the WHOLE cell into a parameter that takes a file, do not parse it. path and url are each present only when the file has one."}
  }'::jsonb,
  concepts = (
    SELECT COALESCE(jsonb_agg(concept), '[]'::jsonb)
    FROM jsonb_array_elements(COALESCE(node_type_documentation.concepts, '[]'::jsonb)) AS concept
    WHERE concept #>> '{}' NOT LIKE 'A file/image column comes back%'
      AND concept #>> '{}' NOT LIKE 'Do NOT store a file reference as JSON text%'
      AND concept #>> '{}' NOT LIKE 'A node that reads the file%'
      AND concept #>> '{}' NOT LIKE 'CHANGED:%'
  ) || '["A file/image column comes back as a usable file reference: map the whole cell, {{table:<label>.output.rows[0].<column>}}, into a public_link node, a media node, or any tool parameter that takes a file", "Do NOT store a file reference as JSON text in a text column and parse it in a code node - a file/image column removes the parse step", "A node that reads the file''s bytes needs the cell''s path, and a cell carries one only when the file it names has a storage path - a link to somewhere else never does, and neither does a file this workspace knows only by id", "CHANGED: these cells used to arrive as a JSON string. In an existing workflow, delete any code node that parses one, stop passing one as a where value (it now matches nothing) and stop copying one into a text column via set. An extract_from_file node pointed at a cell whose file has no stored path now fails instead of extracting the references own text"]'::jsonb,
  updated_at = NOW()
WHERE type = 'get_rows';
