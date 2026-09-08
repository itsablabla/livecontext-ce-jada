package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Re-inflates media cells, so a {@code file} / {@code image} column hands back the FileRef OBJECT
 * its writer produced instead of the JSON text the JSONB write flattened it into.
 *
 * <p>Why this is needed at all: {@code CrudRepository.serializeIfComplex} stringifies every Map
 * before the insert (the JDBC driver cannot bind one to {@code jsonb_build_object}), so the
 * canonical asset {@code {_type:'file', id, path, url, name, mimeType, size}} that
 * {@link ColumnValueCoercer} normalises on the way IN came back OUT as a String. Every reader then
 * had to re-parse it: a workflow could not map a media cell straight into a node parameter that
 * takes a file, and an agent that wanted a usable reference wrote JSON by hand into a text column
 * instead of using the column type built for exactly that.
 *
 * <p>It lives in its own component because TWO services hand rows to the outside world and they
 * must agree: the CRUD service (reads, and the row snapshots its writes publish) and the enhanced
 * service (the grid's own writes, which publish the same row events). A row that FIRES a workflow
 * has to look like a row the workflow READS, and before this was shared that depended on which
 * route happened to write the row.
 *
 * <p>Driven by the DECLARED column type, never by the shape of the value. A text column holding
 * JSON - including a hand-written FileRef, which is what workflows did before this existed - is
 * returned byte for byte as it was, so everything that parses one today keeps working.
 *
 * <p>Where it applies: every read and every row event of the CRUD service, every row event of the
 * enhanced service, and the raw items endpoint WHEN THE CALLER ASKS - a runtime reader (a table
 * trigger's data[], the interface render) asks, a copier (the publication snapshot, and the live
 * side of the moderation diff) deliberately does not, because it is compared against a stored copy
 * and a different encoding on one side would read as a change on every media cell.
 *
 * <p>Still returning the stored text, unchanged from before this class existed: the grid's own
 * paginated query and the server-side export. That is not a claim that every consumer is
 * encoding-agnostic - the interface file-URL rewriters in the FRONTEND
 * ({@code interfaceHtmlUtils.ts}, {@code useInterfaceFileUrls.ts}) only recognise an object and
 * pass a string through untouched - it is that those two routes behave exactly as they always did.
 *
 * <p>Deliberate limits, all of them visible to a caller:
 * <ol>
 *   <li>The value handed back is the CANONICAL asset: keys outside the contract are dropped, and
 *       when the cell names one of our files by id the {@code url} is rebuilt from that id rather
 *       than echoed. A reader gets exactly the shape it would get if the cell were re-saved today,
 *       which is the point of having one contract, but it is NOT a byte-identical echo of the
 *       stored value.</li>
 *   <li>ONE reference per cell: only a JSON object inflates, so a media column holding a JSON
 *       ARRAY keeps its stored text.</li>
 *   <li>It runs AFTER the query, so a {@code where} still matches against the STORED text. Feeding
 *       an inflated cell back in as a {@code where} value therefore matches nothing - filter and
 *       de-duplicate on a text or id column, never on a media cell.</li>
 *   <li>One JSON parse per text-encoded media cell returned, bounded by the page cap that already
 *       bounds the response, and paid only by tables that declare a media column.</li>
 * </ol>
 */
@Component
public class MediaCellHydrator {

    private static final Logger log = LoggerFactory.getLogger(MediaCellHydrator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ColumnValueCoercer columnValueCoercer;

    public MediaCellHydrator(ColumnValueCoercer columnValueCoercer) {
        this.columnValueCoercer = columnValueCoercer;
    }

    /** The media columns of a table, or an empty list when it declares none. */
    public List<String> mediaColumns(Map<String, ColumnMappingSpec> mappingSpec) {
        if (mappingSpec == null || mappingSpec.isEmpty()) {
            return List.of();
        }
        return mappingSpec.entrySet().stream()
            .filter(entry -> entry.getValue() != null
                && (entry.getValue().type() == ColumnType.FILE || entry.getValue().type() == ColumnType.IMAGE))
            // A spec key may carry the "data." prefix a caller wrote; row keys never do.
            .map(entry -> stripDataPrefix(entry.getKey()))
            .toList();
    }

    /** Inflate every media cell of every row, in place. Returns the same rows for chaining. */
    public <T extends java.util.Collection<Map<String, Object>>> T hydrateRows(
            T rows, Map<String, ColumnMappingSpec> mappingSpec) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<String> mediaColumns = mediaColumns(mappingSpec);
        if (mediaColumns.isEmpty()) {
            return rows;
        }
        for (Map<String, Object> row : rows) {
            hydrateRow(row, mediaColumns);
        }
        return rows;
    }

    /** Inflate every media cell of ONE row, in place. Returns the same map for chaining. */
    public Map<String, Object> hydrateRow(Map<String, Object> row,
                                          Map<String, ColumnMappingSpec> mappingSpec) {
        if (row == null) {
            return null;
        }
        return hydrateRow(row, mediaColumns(mappingSpec));
    }

    private Map<String, Object> hydrateRow(Map<String, Object> row, List<String> mediaColumns) {
        for (String column : mediaColumns) {
            // containsKey, not a null check: a row that simply does not carry the column must not
            // gain a null entry it never had.
            if (!row.containsKey(column)) {
                continue;
            }
            Object stored = row.get(column);
            Object hydrated = hydrateValue(stored);
            if (hydrated != stored) {
                row.put(column, hydrated);
            }
        }
        return row;
    }

    /**
     * One media cell. Returns the value unchanged unless it is a serialized asset map.
     *
     * <p>Only a JSON OBJECT (as text, or already parsed) is inflated. A bare URL string is
     * deliberately left alone: it already resolves everywhere a string is expected, and rewriting
     * free text into an object on the read path would change what an interface template, an export
     * or a sort key sees for a value none of our writers produced.
     */
    Object hydrateValue(Object stored) {
        // Decided BEFORE coercing, on the value as stored. The normaliser answers with a map even
        // when it recognised nothing, and it reads generic aliases (key, src, href, link) that an
        // ordinary object carries for its own reasons - so judging its OUTPUT would claim
        // {"key":"abc","value":42} as a file and strip it to the canonical seven fields, deleting
        // the rest on the read path while the database still holds it.
        //
        // The parsed map is carried forward rather than re-derived: coercing the raw text instead
        // would parse the same string a second time, on every media cell of every page.
        Map<?, ?> candidate = fileShapedCandidate(stored);
        if (candidate == null) {
            log.debug("Media cell kept as stored: nothing in it names a file");
            return stored;
        }
        CoercionResult result = columnValueCoercer.coerce(candidate, ColumnType.FILE);
        if (result == null || !(result.value() instanceof Map<?, ?> normalized)) {
            return stored;
        }
        // Even a value that names a file can normalise to nothing addressable; keep the original
        // rather than hand back a bare discriminator.
        if (!normalized.containsKey("id") && !normalized.containsKey("path")
                && !normalized.containsKey("url")) {
            log.debug("Media cell kept as stored: no file reference could be resolved from it");
            return stored;
        }
        return normalized;
    }

    /**
     * The keys that mean "this object is a file", as opposed to the generic ones
     * {@link ColumnValueCoercer} also reads ({@code key}, {@code src}, {@code href}, {@code link},
     * a bare {@code id}) which any object may carry for its own reasons. Deliberately strict: a
     * value we do not recognise keeps the encoding it was stored in, which every reader already
     * handled before this class existed.
     */
    private static final java.util.regex.Pattern OUR_ID = java.util.regex.Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final java.util.Set<String> FILE_MARKERS = java.util.Set.of(
        "path", "storageKey", "storage_key", "s3Key", "s3_key",
        "url", "file_url", "storage_id", "storageId", "fileId");

    /**
     * The stored value as a map when it names a file, else null. Parses a text-encoded cell exactly
     * once - the caller coerces the map this returns, not the text.
     */
    private Map<?, ?> fileShapedCandidate(Object stored) {
        Map<?, ?> map;
        if (stored instanceof Map<?, ?> m) {
            map = m;
        } else if (stored instanceof String text && text.stripLeading().startsWith("{")) {
            try {
                map = OBJECT_MAPPER.readValue(text, Map.class);
            } catch (Exception e) {
                return null;
            }
        } else {
            // Anything else - a bare URL, a JSON array, a number - keeps the encoding it was
            // stored in: one cell holds one reference, and free text is not reinterpreted.
            return null;
        }
        if (ColumnValueCoercer.FILE_REF_TYPE.equals(map.get("_type"))) {
            return map;
        }
        // A UUID id addresses one of OUR files and nothing else does, so it names a file on its
        // own. Any OTHER id is somebody else's key (an Airtable attachment, a third-party record)
        // and is deliberately not evidence - which is the same rule the normaliser applies.
        if (map.get("id") instanceof String id && OUR_ID.matcher(id).matches()) {
            return map;
        }
        for (Object key : map.keySet()) {
            if (key instanceof String name && FILE_MARKERS.contains(name)) {
                return map;
            }
        }
        return null;
    }

    private static String stripDataPrefix(String column) {
        return (column != null && column.startsWith("data.")) ? column.substring("data.".length()) : column;
    }
}
