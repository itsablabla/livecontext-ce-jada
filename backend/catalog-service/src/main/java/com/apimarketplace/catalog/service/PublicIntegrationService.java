package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.dto.PublicIntegrationDTO;
import com.apimarketplace.catalog.dto.PublicIntegrationDetailDTO;
import com.apimarketplace.catalog.dto.PublicIntegrationToolDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads of the integration catalog for the PUBLIC, unauthenticated pages
 * (the landing section, /integrations and /integrations/{slug}).
 *
 * <p>Separate from {@link WorkflowInspectorService} on purpose, even though the ranked
 * query looks similar. The inspector answers a signed-in builder and may show that
 * tenant's own private APIs; this one answers anyone on the internet and must never
 * show a private one. Sharing a method between the two would mean one WHERE clause
 * deciding both, and the day it drifts the visible failure is a stranger reading the
 * name and description of an API someone registered privately. Two readers, two
 * clauses, and the public clause is asserted by its own test.
 *
 * <h2>What "public" means here</h2>
 * <ul>
 *   <li>{@code visibility = 'public'} - the same column
 *       {@code LexicalSearchIndexRepository} uses for anonymous search, and the one
 *       {@code CustomApiRegistrationService} sets to {@code 'private'} for a
 *       user-registered API. NOT {@code is_public}, which defaults to false and is
 *       left false by the catalog importer, so filtering on it would publish an
 *       empty directory.</li>
 *   <li>{@code is_active = true} and no {@code deprecated_at} - a retired integration
 *       must not get a crawlable page advertising it.</li>
 *   <li>At least one active endpoint. An integration with no tool cannot be used, so
 *       listing it would be a page promising something the product cannot do.</li>
 *   <li>A non-null {@code api_slug}. It is nullable in the schema, and a row without
 *       one has no URL: counting it would inflate the catalog size the pages state
 *       while producing no card and no page.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicIntegrationService {

    private final JdbcTemplate jdbcTemplate;

    /** Hard ceiling on one page, so a crafted {@code size} cannot ask for the world. */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Longest search term accepted. Anything longer is truncated rather than rejected:
     * a search box must not answer an error to a paste.
     */
    private static final int MAX_QUERY_LENGTH = 80;

    /**
     * Endpoints listed on one integration page. The biggest integrations in the catalog
     * carry several hundred; rendering all of them turns a page into a wall and inflates
     * the HTML past what the content is worth. The page says when it truncates.
     */
    private static final int MAX_TOOLS_PER_PAGE = 120;

    /**
     * The shared visibility gate. Every public read below concatenates THIS constant,
     * so there is exactly one place where "what an anonymous visitor may see" is
     * defined. {@code a} is the {@code catalog.apis} alias.
     */
    private static final String PUBLIC_API_PREDICATE = """
        a.is_active = true
        AND a.visibility = 'public'
        AND a.deprecated_at IS NULL
        AND a.api_slug IS NOT NULL
        """;

    /** Active, non-retired endpoints of the joined API. {@code t} is the {@code api_tools} alias. */
    private static final String ACTIVE_TOOL_PREDICATE = "t.is_active = true AND t.deprecated_at IS NULL";

    /**
     * One page of public integrations, most-RUN first.
     *
     * <p>The ORDER comes from {@code catalog.api_usage_stats}, the pre-aggregated
     * node-usage ledger, so ranking costs an indexed lookup rather than a walk of run
     * history. The QUERY is not cheap for all that: {@code COUNT(t.id)} with
     * {@code HAVING COUNT(t.id) > 0} makes Postgres scan every active row of
     * {@code api_tools} (measured on real data: a sequential scan of ~31,700 rows,
     * ~35 ms), and the controller runs this and {@link #count} per request. That is
     * affordable behind the caller's cache windows and the gateway's anonymous rate
     * limit, and it is the thing to fix first if this ever needs to be cheaper: keep a
     * maintained tool count beside the usage row so the join disappears from this path.
     *
     * <p>Never-run integrations are included, after the run ones and alphabetical among
     * themselves, so the directory is the WHOLE public catalog in usage order rather
     * than only the part that has been used. The tie-break is {@code api_slug} rather
     * than the display name: names are duplicated across the catalog, so a name
     * tie-break would leave the order of a tied pair undefined and let them swap places
     * between two page fetches, appearing twice or not at all. {@code api_slug} carries
     * no UNIQUE constraint either, so this makes collisions vanishingly unlikely rather
     * than impossible; grouping on {@code a.id} is what keeps two same-slug rows from
     * collapsing into one.
     *
     * @param query optional case-insensitive filter on name, slug and description;
     *              blank means "no filter"
     */
    @Transactional(readOnly = true)
    public List<PublicIntegrationDTO> list(int page, int size, String query) {
        int safeSize = clampSize(size);
        int safePage = Math.max(0, page);
        String term = normalizeQuery(query);

        List<Object> params = new ArrayList<>();
        String sql = listSql(term, params);
        params.add(safeSize);
        params.add((long) safePage * safeSize);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        List<PublicIntegrationDTO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(toSummary(row));
        }
        return result;
    }

    /**
     * How many public integrations match {@code query}, for the pager and for the
     * catalog size the public pages state.
     *
     * <p>Counts the GROUP BY groups rather than the rows: the tool join multiplies
     * an API by its endpoint count, so a plain {@code COUNT(*)} here would report tens
     * of thousands of integrations.
     */
    @Transactional(readOnly = true)
    public int count(String query) {
        String term = normalizeQuery(query);
        List<Object> params = new ArrayList<>();
        String sql = countSql(term, params);
        Integer total = jdbcTemplate.queryForObject(sql, Integer.class, params.toArray());
        return total == null ? 0 : total;
    }

    /**
     * One public integration and its endpoints, or empty when the slug is unknown,
     * private, retired, or exposes no active endpoint.
     *
     * <p>Empty and "not public" are deliberately indistinguishable to the caller: the
     * controller turns both into a 404, so probing this endpoint cannot tell a visitor
     * whether a private integration by that name exists.
     */
    @Transactional(readOnly = true)
    public Optional<PublicIntegrationDetailDTO> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }

        List<Map<String, Object>> heads = jdbcTemplate.queryForList(detailHeadSql(), slug);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> head = heads.get(0);
        PublicIntegrationDTO summary = toSummary(head);

        // One extra row is requested so "there are more" is observed, not guessed from
        // toolCount: the two come from the same query in practice, but a page that
        // claims truncation it cannot see is a page that will one day claim it wrongly.
        List<Map<String, Object>> toolRows =
            jdbcTemplate.queryForList(toolsSql(), head.get("id"), MAX_TOOLS_PER_PAGE + 1);

        boolean truncated = toolRows.size() > MAX_TOOLS_PER_PAGE;
        List<PublicIntegrationToolDTO> tools = new ArrayList<>();
        for (Map<String, Object> row : toolRows.subList(0, Math.min(toolRows.size(), MAX_TOOLS_PER_PAGE))) {
            tools.add(new PublicIntegrationToolDTO(
                (String) row.get("tool_name"),
                (String) row.get("description"),
                (String) row.get("method")
            ));
        }

        return Optional.of(new PublicIntegrationDetailDTO(
            summary,
            (String) head.get("documentation"),
            tools,
            truncated
        ));
    }

    // ===== SQL builders =====
    //
    // The queries are built by these pure methods rather than inline, so their tests can
    // assert the WHERE clause DIRECTLY instead of through a mocked JdbcTemplate. That is
    // not a style preference: JdbcTemplate overloads differ only by their varargs tail, so
    // a matcher expression can silently bind to a different overload than the one the
    // service calls, and the resulting test then passes while asserting nothing. The clause
    // that decides what a stranger may read is not something to verify by accident.

    /**
     * The ranked list query. Appends the search binds (if any) to {@code params}; the
     * caller appends LIMIT and OFFSET after, in that order.
     */
    static String listSql(String term, List<Object> params) {
        return """
            SELECT
                a.api_slug,
                a.api_name,
                a.description,
                COALESCE(a.icon_slug, 'mcp') AS icon_slug,
                a.icon_url,
                a.auth_type,
                COUNT(t.id) AS tool_count,
                COALESCE(u.run_count, 0) AS run_count
            FROM catalog.apis a
            LEFT JOIN catalog.api_tools t ON t.api_id = a.id AND
            """ + ACTIVE_TOOL_PREDICATE + """

            LEFT JOIN catalog.api_usage_stats u ON u.api_slug = a.api_slug
            WHERE
            """ + PUBLIC_API_PREDICATE + searchClause(term, params) + """

            GROUP BY a.id, u.run_count
            HAVING COUNT(t.id) > 0
            ORDER BY COALESCE(u.run_count, 0) DESC, a.api_slug ASC
            LIMIT ? OFFSET ?
            """;
    }

    /** The matching-row count, over the same gate and the same search clause as {@link #listSql}. */
    static String countSql(String term, List<Object> params) {
        return """
            SELECT COUNT(*) FROM (
                SELECT a.id
                FROM catalog.apis a
                LEFT JOIN catalog.api_tools t ON t.api_id = a.id AND
                """ + ACTIVE_TOOL_PREDICATE + """

                WHERE
                """ + PUBLIC_API_PREDICATE + searchClause(term, params) + """

                GROUP BY a.id
                HAVING COUNT(t.id) > 0
            ) matched
            """;
    }

    /** One integration by slug, under the same public gate. Binds: the slug. */
    static String detailHeadSql() {
        return """
            SELECT
                a.id,
                a.api_slug,
                a.api_name,
                a.description,
                COALESCE(a.icon_slug, 'mcp') AS icon_slug,
                a.icon_url,
                a.auth_type,
                a.documentation,
                COUNT(t.id) AS tool_count
            FROM catalog.apis a
            LEFT JOIN catalog.api_tools t ON t.api_id = a.id AND
            """ + ACTIVE_TOOL_PREDICATE + """

            WHERE
            """ + PUBLIC_API_PREDICATE + """
            AND a.api_slug = ?
            GROUP BY a.id
            HAVING COUNT(t.id) > 0
            """;
    }

    /**
     * The endpoints of one integration. Binds: the api id, then the row limit.
     *
     * <p>No visibility clause here, and none is needed: it is only ever reached with an id
     * that {@link #detailHeadSql} already resolved under the gate.
     */
    static String toolsSql() {
        return """
            SELECT COALESCE(tn.name, t.tool_slug) AS tool_name, t.description, t.method
            FROM catalog.api_tools t
            LEFT JOIN catalog.tool_names tn ON tn.id::text = t.tool_name_id
            WHERE t.api_id = ? AND
            """ + ACTIVE_TOOL_PREDICATE + """

            ORDER BY COALESCE(tn.name, t.tool_slug) ASC
            LIMIT ?
            """;
    }

    // ===== helpers =====

    private static PublicIntegrationDTO toSummary(Map<String, Object> row) {
        Number toolCount = (Number) row.get("tool_count");
        return new PublicIntegrationDTO(
            (String) row.get("api_slug"),
            (String) row.get("api_name"),
            (String) row.get("description"),
            (String) row.get("icon_slug"),
            (String) row.get("icon_url"),
            toolCount == null ? 0 : toolCount.intValue(),
            (String) row.get("auth_type")
        );
    }

    public static int clampSize(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    /**
     * Trim, lowercase and cap a caller-supplied search term; blank becomes null so
     * callers can test for "no filter" with one null check.
     */
    static String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_QUERY_LENGTH);
        }
        return trimmed.toLowerCase();
    }

    /**
     * The SQL fragment for a search term, appending its bind parameters to {@code params}.
     *
     * <p>Returns the empty string when there is no term, so the caller concatenates
     * nothing rather than a always-true clause.
     */
    private static String searchClause(String term, List<Object> params) {
        if (term == null) {
            return "";
        }
        String pattern = "%" + escapeLike(term) + "%";
        params.add(pattern);
        params.add(pattern);
        params.add(pattern);
        return """
            AND (
                LOWER(a.api_name) LIKE ? ESCAPE '!'
                OR LOWER(a.api_slug) LIKE ? ESCAPE '!'
                OR LOWER(COALESCE(a.description, '')) LIKE ? ESCAPE '!'
            )
            """;
    }

    /**
     * Neutralise the LIKE metacharacters in a user term.
     *
     * <p>Without this, a visitor typing {@code %} matches the whole catalog and one typing
     * {@code _} matches every one-character difference: the search silently stops
     * searching. {@code !} is the declared escape character, so it is escaped first -
     * escaping it last would double-escape the escapes introduced for {@code %} and
     * {@code _}.
     */
    static String escapeLike(String term) {
        return term.replace("!", "!!")
                   .replace("%", "!%")
                   .replace("_", "!_");
    }
}
