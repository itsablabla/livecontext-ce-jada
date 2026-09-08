package com.apimarketplace.catalog.usage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Owns the catalog half of the node-usage ledger (V461): how often each ENDPOINT was
 * launched, and the same total rolled up per INTEGRATION.
 *
 * <p>Why this lives in catalog-service. The orchestrator counts launches but cannot say
 * which API an endpoint belongs to: a {@code tool_slug} is DERIVED at import
 * ({@code apiSlug + "-" + slugify(name)}, plus a uniqueness suffix on collision), so it
 * carries no boundary a string split could find, and no service may read another
 * service's schema. The orchestrator therefore reports identifiers verbatim and this
 * service resolves them, using the SAME three accepted forms as tool execution
 * ({@code ToolContextService.loadToolContext}): a UUID, {@code apiSlug/toolSlug}, or a
 * bare {@code toolSlug}. Keeping the two resolvers in step is what stops the ranking
 * from silently missing a whole class of node.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolUsageStatsService {

    /** Same shape {@code ToolContextService} treats as an {@code api_tools.id}. */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Ceiling on identifiers accepted in one report. A flush window carries the DISTINCT
     * endpoints a fleet launched in a minute, which is orders of magnitude below this;
     * the bound exists so a malformed caller cannot make the resolver build an unbounded
     * {@code IN} list.
     */
    static final int MAX_IDENTIFIERS_PER_REPORT = 2_000;

    private static final String UPSERT_TOOL = """
            INSERT INTO catalog.tool_usage_stats (tool_slug, api_slug, run_count, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (tool_slug) DO UPDATE
            SET run_count  = catalog.tool_usage_stats.run_count + EXCLUDED.run_count,
                api_slug   = EXCLUDED.api_slug,
                updated_at = now()
            """;

    private static final String UPSERT_API = """
            INSERT INTO catalog.api_usage_stats (api_slug, run_count, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (api_slug) DO UPDATE
            SET run_count  = catalog.api_usage_stats.run_count + EXCLUDED.run_count,
                updated_at = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Apply one reported window.
     *
     * <p>Both levels are written in ONE transaction from ONE resolved map, so the API
     * total is always the sum of the endpoint rows that produced it. Unresolvable
     * identifiers are dropped, not guessed: an endpoint removed by a re-import, or a
     * malformed reference, must not invent a row that then ranks an integration.
     *
     * @param counts identifier to launch count for the window
     * @return how many launches were actually attributed
     */
    @Transactional
    public long record(Map<String, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        if (counts.size() > MAX_IDENTIFIERS_PER_REPORT) {
            log.warn("Tool usage report holds {} identifiers, over the {} ceiling - ignored",
                    counts.size(), MAX_IDENTIFIERS_PER_REPORT);
            return 0;
        }

        Map<String, ToolRow> resolved = resolve(counts.keySet());
        if (resolved.isEmpty()) {
            log.debug("Tool usage report resolved to no known endpoint ({} identifiers)", counts.size());
            return 0;
        }

        // Two identifiers can name the SAME endpoint (its UUID and its slug), so the
        // deltas are merged per resolved row before any write - upserting twice would
        // double count in tool_usage_stats and once more in api_usage_stats.
        Map<String, Long> byToolSlug = new LinkedHashMap<>();
        Map<String, String> apiOfTool = new HashMap<>();
        Map<String, Long> byApiSlug = new LinkedHashMap<>();
        long attributed = 0;

        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            Long delta = entry.getValue();
            if (delta == null || delta <= 0) {
                continue;
            }
            ToolRow row = resolved.get(entry.getKey());
            if (row == null) {
                continue;
            }
            byToolSlug.merge(row.toolSlug(), delta, Long::sum);
            apiOfTool.put(row.toolSlug(), row.apiSlug());
            byApiSlug.merge(row.apiSlug(), delta, Long::sum);
            attributed += delta;
        }
        if (byToolSlug.isEmpty()) {
            return 0;
        }

        // Rows are locked in SLUG ORDER, in both statements. Several orchestrator pods
        // flush overlapping windows concurrently, and two transactions that touch the
        // same rows in different orders deadlock: one is aborted by the database and
        // its window is retried a minute later for no reason. A total order costs a
        // sort of a few hundred entries and removes the cycle entirely.
        List<Object[]> toolBatch = new ArrayList<>(byToolSlug.size());
        byToolSlug.keySet().stream().sorted()
                .forEach(slug -> toolBatch.add(new Object[]{slug, apiOfTool.get(slug), byToolSlug.get(slug)}));
        jdbcTemplate.batchUpdate(UPSERT_TOOL, toolBatch);

        List<Object[]> apiBatch = new ArrayList<>(byApiSlug.size());
        byApiSlug.keySet().stream().sorted()
                .forEach(slug -> apiBatch.add(new Object[]{slug, byApiSlug.get(slug)}));
        jdbcTemplate.batchUpdate(UPSERT_API, apiBatch);

        log.debug("Tool usage recorded: {} endpoint(s) over {} integration(s), {} launches",
                toolBatch.size(), apiBatch.size(), attributed);
        return attributed;
    }

    /**
     * Map every accepted identifier form to its canonical endpoint row, in two queries
     * whatever the report's size (one by UUID, one by slug) rather than one per entry.
     */
    private Map<String, ToolRow> resolve(Set<String> identifiers) {
        Set<String> uuids = new HashSet<>();
        Set<String> slugs = new HashSet<>();
        Map<String, String> slugOfIdentifier = new HashMap<>();

        for (String raw : identifiers) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String identifier = raw.trim();
            if (UUID_PATTERN.matcher(identifier).matches()) {
                uuids.add(identifier);
                continue;
            }
            // "apiSlug/toolSlug" carries the API for readability only: the tool slug is
            // already unique, and trusting the prefix would attribute a renamed API's
            // runs to a slug that no longer exists.
            int separator = identifier.lastIndexOf('/');
            String slug = separator >= 0 ? identifier.substring(separator + 1) : identifier;
            if (slug.isBlank()) {
                continue;
            }
            slugs.add(slug);
            slugOfIdentifier.put(identifier, slug);
        }

        Map<String, ToolRow> byUuid = new HashMap<>();
        Map<String, ToolRow> bySlug = new HashMap<>();
        if (!uuids.isEmpty()) {
            query("SELECT t.id::text AS ref, t.tool_slug, a.api_slug"
                            + " FROM catalog.api_tools t JOIN catalog.apis a ON a.id = t.api_id"
                            + " WHERE t.id::text IN (" + placeholders(uuids.size()) + ")",
                    uuids, byUuid);
        }
        if (!slugs.isEmpty()) {
            query("SELECT t.tool_slug AS ref, t.tool_slug, a.api_slug"
                            + " FROM catalog.api_tools t JOIN catalog.apis a ON a.id = t.api_id"
                            + " WHERE t.tool_slug IN (" + placeholders(slugs.size()) + ")",
                    slugs, bySlug);
        }

        Map<String, ToolRow> resolved = new HashMap<>(byUuid);
        slugOfIdentifier.forEach((identifier, slug) -> {
            ToolRow row = bySlug.get(slug);
            if (row != null) {
                resolved.put(identifier, row);
            }
        });
        return resolved;
    }

    private void query(String sql, Set<String> params, Map<String, ToolRow> into) {
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, params.toArray())) {
            String toolSlug = (String) row.get("tool_slug");
            String apiSlug = (String) row.get("api_slug");
            String ref = (String) row.get("ref");
            if (toolSlug != null && apiSlug != null && ref != null) {
                into.put(ref, new ToolRow(toolSlug, apiSlug));
            }
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** One resolved endpoint: its canonical slug and the integration it belongs to. */
    private record ToolRow(String toolSlug, String apiSlug) {}
}
