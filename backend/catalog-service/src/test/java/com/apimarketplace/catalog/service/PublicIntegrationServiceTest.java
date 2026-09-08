package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.dto.PublicIntegrationDTO;
import com.apimarketplace.catalog.dto.PublicIntegrationDetailDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the public integration directory's reads.
 *
 * <p>The SQL assertions go through the pure builders rather than a mocked JdbcTemplate.
 * That is deliberate: JdbcTemplate's overloads differ only by their varargs tail, so a
 * matcher expression can bind to a different overload than the service calls, and the
 * test then passes while asserting nothing at all. The clause deciding what a stranger
 * may read is the last thing to verify by accident.
 *
 * <p>Mockito is still used where the behaviour IS the interaction (bind order,
 * truncation), and there each stub declares exactly as many {@link #anyArg()} matchers
 * as the call it stands for, because Mockito expands varargs before matching.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PublicIntegrationService")
class PublicIntegrationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PublicIntegrationService service;

    /**
     * A matcher pinned to {@code Object}.
     *
     * <p>A bare {@code any()} infers its type from the call site, which lets the compiler
     * see {@code queryForList(String, Class<T>, Object...)} as a candidate and rejects the
     * expression as ambiguous. Pinning it to Object leaves only the varargs overload the
     * service actually calls.
     */
    private static Object anyArg() {
        return ArgumentMatchers.<Object>any();
    }

    /** The unfiltered list query, as the service builds it. */
    private static String unfilteredListSql() {
        return PublicIntegrationService.listSql(null, new ArrayList<>());
    }

    /** One row shaped exactly as the list and head queries project it. */
    private static Map<String, Object> row(String slug, String name, int toolCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("api_slug", slug);
        row.put("api_name", name);
        row.put("description", name + " does things");
        row.put("icon_slug", slug);
        row.put("icon_url", null);
        row.put("auth_type", "bearer_token");
        row.put("tool_count", toolCount);
        row.put("run_count", 0);
        row.put("documentation", "https://example.test/docs");
        return row;
    }

    private static Map<String, Object> toolRow(String name, String method) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tool_name", name);
        row.put("description", name + " description");
        row.put("method", method);
        return row;
    }

    private static List<Map<String, Object>> toolRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(toolRow("op_" + i, "GET"));
        }
        return rows;
    }

    /** Component names of the public DTO, so the "no run counts" assertion reads what it means. */
    private static List<String> publicDtoComponentNames() {
        return Arrays.stream(PublicIntegrationDTO.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }

    @Nested
    @DisplayName("the visibility gate")
    class VisibilityGate {

        @Test
        @DisplayName("the list query serves only integrations marked visibility='public'")
        void listFiltersOnPublicVisibility() {
            // The whole point of this service: an anonymous page must never surface an API
            // a user registered privately (CustomApiRegistrationService writes
            // visibility='private'). Without this clause the directory publishes them.
            assertThat(unfilteredListSql()).contains("a.visibility = 'public'");
        }

        @Test
        @DisplayName("does not fall back to is_public, which the importer leaves false")
        void listDoesNotUseIsPublicColumn() {
            // apis.is_public defaults to false and the catalog importer never sets it, so
            // filtering on it would publish an EMPTY directory while looking correct.
            assertThat(unfilteredListSql()).doesNotContain("is_public");
        }

        @Test
        @DisplayName("excludes inactive and retired integrations")
        void listExcludesInactiveAndDeprecated() {
            assertThat(unfilteredListSql()).contains("a.is_active = true");
            assertThat(unfilteredListSql()).contains("a.deprecated_at IS NULL");
        }

        @Test
        @DisplayName("excludes integrations that expose no active endpoint")
        void listRequiresAtLeastOneTool() {
            // A page for an integration with zero tools promises something the product
            // cannot do, so it must not be listed and must not get a URL.
            assertThat(unfilteredListSql()).contains("HAVING COUNT(t.id) > 0");
        }

        @Test
        @DisplayName("every public query carries the same gate, so no read can be the loose one")
        void everyQueryIsGated() {
            List<String> publicQueries = List.of(
                unfilteredListSql(),
                PublicIntegrationService.countSql(null, new ArrayList<>()),
                PublicIntegrationService.detailHeadSql());

            for (String sql : publicQueries) {
                // A directory that hides private APIs but serves them at
                // /integrations/{slug} hides nothing: guessing one slug is enough. And a
                // count that ignores the gate makes the pager advertise pages that are
                // empty by the time they render.
                assertThat(sql).contains("a.visibility = 'public'");
                assertThat(sql).contains("a.is_active = true");
                assertThat(sql).contains("a.deprecated_at IS NULL");
                assertThat(sql).contains("HAVING COUNT(t.id) > 0");
            }
        }

        @Test
        @DisplayName("requires a slug, so the count cannot include a row with no page")
        void requiresASlug() {
            // api_slug is nullable. Such a row has no URL, so the frontend mapper drops
            // it: counting it would inflate the catalog size the pages state while
            // producing no card and no page.
            for (String sql : List.of(unfilteredListSql(),
                    PublicIntegrationService.countSql(null, new ArrayList<>()),
                    PublicIntegrationService.detailHeadSql())) {
                assertThat(sql).contains("a.api_slug IS NOT NULL");
            }
        }

        @Test
        @DisplayName("the list and the count group on the same key, so they cannot disagree")
        void listAndCountGroupAlike() {
            // Grouping the list on api_slug while the count grouped on a.id meant two
            // rows sharing a slug collapsed into one card but counted as two.
            assertThat(unfilteredListSql()).contains("GROUP BY a.id");
            assertThat(PublicIntegrationService.countSql(null, new ArrayList<>()))
                .contains("GROUP BY a.id");
        }

        @Test
        @DisplayName("the endpoint query is reached only through an already-gated id")
        void toolQueryInheritsTheGate() {
            // It carries no visibility clause of its own, deliberately: it takes an api id
            // that detailHeadSql resolved under the gate. Asserted so that a later change
            // routing it from anywhere else has to confront this comment.
            assertThat(PublicIntegrationService.toolsSql()).contains("WHERE t.api_id = ?");
            assertThat(PublicIntegrationService.toolsSql()).doesNotContain("visibility");
        }
    }

    @Nested
    @DisplayName("the generated SQL")
    class GeneratedSql {

        @Test
        @DisplayName("keeps a separator between every concatenated fragment")
        void keepsSeparatorsBetweenFragments() {
            // Regression guard for a bug this suite caught. The predicates are concatenated
            // into Java text blocks, and a text block strips trailing whitespace from every
            // line: writing `WHERE """ + PREDICATE` produced the literal
            // `WHEREa.is_active = true`, a syntax error that an assertion merely looking
            // for "WHERE" would happily pass.
            List<String> queries = List.of(
                unfilteredListSql(),
                PublicIntegrationService.listSql("slack", new ArrayList<>()),
                PublicIntegrationService.countSql(null, new ArrayList<>()),
                PublicIntegrationService.detailHeadSql(),
                PublicIntegrationService.toolsSql());

            for (String sql : queries) {
                assertThat(sql).doesNotContain("WHEREa.");
                assertThat(sql).doesNotContain("ANDt.");
                assertThat(sql).containsPattern("(?s)a\\.id\\s+AND\\s+t\\.is_active|t\\.api_id = \\?\\s+AND\\s+t\\.is_active");
            }
        }

        @Test
        @DisplayName("puts the WHERE keyword on its own before the predicate")
        void whereKeywordIsSeparated() {
            assertThat(unfilteredListSql()).containsPattern("(?s)WHERE\\s+a\\.is_active");
            assertThat(PublicIntegrationService.detailHeadSql()).containsPattern("(?s)WHERE\\s+a\\.is_active");
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("orders by run count first, then by slug so paging is stable")
        void ordersByUsageThenSlug() {
            // The slug tie-break (not the display name) is what makes the order total: two
            // integrations sharing a name could otherwise swap places between two page
            // fetches and appear twice, or not at all.
            assertThat(unfilteredListSql())
                .contains("ORDER BY COALESCE(u.run_count, 0) DESC, a.api_slug ASC");
        }

        @Test
        @DisplayName("keeps never-run integrations in the list, ranked last")
        void includesNeverRunIntegrations() {
            // A LEFT JOIN plus COALESCE: an INNER JOIN here would silently shrink the
            // directory to whatever has been run so far.
            assertThat(unfilteredListSql()).contains("LEFT JOIN catalog.api_usage_stats");
            assertThat(unfilteredListSql()).contains("COALESCE(u.run_count, 0)");
        }

        @Test
        @DisplayName("maps a row into the public DTO without publishing the run count")
        void doesNotPublishRunCounts() {
            when(jdbcTemplate.queryForList(anyString(), anyArg(), anyArg()))
                .thenReturn(List.of(row("slack", "Slack", 42)));

            List<PublicIntegrationDTO> results = service.list(0, 20, null);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).slug()).isEqualTo("slack");
            assertThat(results.get(0).name()).isEqualTo("Slack");
            assertThat(results.get(0).description()).isEqualTo("Slack does things");
            assertThat(results.get(0).iconSlug()).isEqualTo("slack");
            assertThat(results.get(0).toolCount()).isEqualTo(42);
            assertThat(results.get(0).authType()).isEqualTo("bearer_token");
            // Order is public, the number behind it is not: the record has no field for it,
            // so no later caller can start rendering internal traffic volumes.
            assertThat(publicDtoComponentNames()).doesNotContain("runCount");
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("binds only the paging parameters when no term is given")
        void noTermBindsOnlyPaging() {
            when(jdbcTemplate.queryForList(anyString(), anyArg(), anyArg())).thenReturn(List.of());

            service.list(2, 25, "   ");

            ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).queryForList(anyString(), args.capture(), args.capture());
            assertThat(args.getAllValues()).containsExactly(25, 50L);
        }

        @Test
        @DisplayName("binds the same escaped pattern to name, slug and description, before paging")
        void termBindsThreePatternsThenPaging() {
            when(jdbcTemplate.queryForList(
                anyString(), anyArg(), anyArg(), anyArg(), anyArg(), anyArg())).thenReturn(List.of());

            service.list(0, 10, "Sla");

            ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).queryForList(
                anyString(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
            // Order matters: the three search binds sit in the WHERE clause and must
            // precede LIMIT/OFFSET, or every page comes back empty.
            assertThat(args.getAllValues()).containsExactly("%sla%", "%sla%", "%sla%", 10, 0L);
        }

        @Test
        @DisplayName("adds no search clause at all when the term is blank")
        void blankTermAddsNoClause() {
            List<Object> params = new ArrayList<>();

            String sql = PublicIntegrationService.listSql(PublicIntegrationService.normalizeQuery(""), params);

            assertThat(sql).doesNotContain("LIKE");
            assertThat(params).isEmpty();
        }

        @Test
        @DisplayName("searches the name, the slug and the description")
        void searchesEveryUserVisibleField() {
            String sql = PublicIntegrationService.listSql("sla", new ArrayList<>());

            // Slug matters because it is what a visitor types from a URL, and description
            // because half the catalogue's brand names say nothing about what they do.
            assertThat(sql).contains("LOWER(a.api_name) LIKE ?");
            assertThat(sql).contains("LOWER(a.api_slug) LIKE ?");
            assertThat(sql).contains("LOWER(COALESCE(a.description, '')) LIKE ?");
        }

        @Test
        @DisplayName("neutralises LIKE wildcards so a typed % does not match everything")
        void escapesLikeWildcards() {
            assertThat(PublicIntegrationService.escapeLike("100%")).isEqualTo("100!%");
            assertThat(PublicIntegrationService.escapeLike("a_b")).isEqualTo("a!_b");
            // The escape character itself is escaped FIRST, so the escapes introduced for
            // % and _ are not double-escaped afterwards.
            assertThat(PublicIntegrationService.escapeLike("!")).isEqualTo("!!");
            assertThat(PublicIntegrationService.escapeLike("!%")).isEqualTo("!!!%");
        }

        @Test
        @DisplayName("declares the escape character it actually uses")
        void declaresItsEscapeCharacter() {
            // escapeLike writes '!' escapes; without ESCAPE '!' in the SQL, Postgres reads
            // them as literal characters and the search quietly stops matching.
            assertThat(PublicIntegrationService.listSql("a_b", new ArrayList<>())).contains("ESCAPE '!'");
        }

        @Test
        @DisplayName("normalises a term: trimmed, lowercased, blank becomes no filter")
        void normalisesTerms() {
            assertThat(PublicIntegrationService.normalizeQuery(null)).isNull();
            assertThat(PublicIntegrationService.normalizeQuery("  ")).isNull();
            assertThat(PublicIntegrationService.normalizeQuery("  SlAcK ")).isEqualTo("slack");
        }

        @Test
        @DisplayName("truncates an over-long term instead of rejecting it")
        void capsTermLength() {
            String pasted = "x".repeat(500);

            String normalized = PublicIntegrationService.normalizeQuery(pasted);

            // A search box that answers an error to a paste is a broken search box.
            assertThat(normalized).hasSize(80);
        }
    }

    @Nested
    @DisplayName("paging bounds")
    class PagingBounds {

        @Test
        @DisplayName("clamps the page size into 1..200")
        void clampsSize() {
            assertThat(PublicIntegrationService.clampSize(0)).isEqualTo(1);
            assertThat(PublicIntegrationService.clampSize(-5)).isEqualTo(1);
            assertThat(PublicIntegrationService.clampSize(60)).isEqualTo(60);
            assertThat(PublicIntegrationService.clampSize(10_000)).isEqualTo(200);
        }

        @Test
        @DisplayName("treats a negative page as the first page")
        void clampsNegativePage() {
            when(jdbcTemplate.queryForList(anyString(), anyArg(), anyArg())).thenReturn(List.of());

            service.list(-3, 10, null);

            ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).queryForList(anyString(), args.capture(), args.capture());
            // A negative OFFSET is a SQL error, not an empty page.
            assertThat(args.getAllValues()).containsExactly(10, 0L);
        }

        @Test
        @DisplayName("applies the clamped size to the offset as well")
        void offsetUsesTheClampedSize() {
            when(jdbcTemplate.queryForList(anyString(), anyArg(), anyArg())).thenReturn(List.of());

            service.list(3, 10_000, null);

            ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).queryForList(anyString(), args.capture(), args.capture());
            // Page 3 of 200-row pages starts at 600. Computing the offset from the
            // REQUESTED size would skip 29,400 rows and serve an empty page forever.
            assertThat(args.getAllValues()).containsExactly(200, 600L);
        }
    }

    @Nested
    @DisplayName("findBySlug")
    class FindBySlug {

        @Test
        @DisplayName("returns empty for a blank slug without querying")
        void blankSlugShortCircuits() {
            assertThat(service.findBySlug("  ")).isEmpty();
            assertThat(service.findBySlug(null)).isEmpty();

            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("returns empty when the slug is unknown, private or retired")
        void unknownSlugIsEmpty() {
            when(jdbcTemplate.queryForList(anyString(), anyArg())).thenReturn(List.of());

            assertThat(service.findBySlug("someones-private-api")).isEmpty();

            // The endpoint list must not be queried once the head produced nothing.
            verify(jdbcTemplate, never()).queryForList(anyString(), anyArg(), anyArg());
        }

        @Test
        @DisplayName("returns the integration with its endpoints")
        void returnsDetail() {
            when(jdbcTemplate.queryForList(anyString(), anyArg()))
                .thenReturn(List.of(row("slack", "Slack", 3)));
            when(jdbcTemplate.queryForList(anyString(), anyArg(), anyArg()))
                .thenReturn(List.of(toolRow("send_message", "POST"), toolRow("list_channels", "GET")));

            Optional<PublicIntegrationDetailDTO> detail = service.findBySlug("slack");

            assertThat(detail).isPresent();
            assertThat(detail.get().integration().name()).isEqualTo("Slack");
            assertThat(detail.get().documentation()).isEqualTo("https://example.test/docs");
            assertThat(detail.get().tools()).extracting("name")
                .containsExactly("send_message", "list_channels");
            assertThat(detail.get().tools()).extracting("method").containsExactly("POST", "GET");
            assertThat(detail.get().toolsTruncated()).isFalse();
        }

        @Test
        @DisplayName("caps the endpoint list at 120 and says it is truncated")
        void truncatesLongToolLists() {
            when(jdbcTemplate.queryForList(anyString(), anyArg()))
                .thenReturn(List.of(row("stripe", "Stripe", 400)));
            when(jdbcTemplate.queryForList(anyString(), anyArg(), anyArg())).thenReturn(toolRows(121));

            Optional<PublicIntegrationDetailDTO> detail = service.findBySlug("stripe");

            assertThat(detail).isPresent();
            assertThat(detail.get().tools()).hasSize(120);
            assertThat(detail.get().toolsTruncated()).isTrue();
        }

        @Test
        @DisplayName("does not claim truncation when the list ends exactly at the cap")
        void exactlyAtCapIsNotTruncated() {
            when(jdbcTemplate.queryForList(anyString(), anyArg()))
                .thenReturn(List.of(row("stripe", "Stripe", 120)));
            when(jdbcTemplate.queryForList(anyString(), anyArg(), anyArg())).thenReturn(toolRows(120));

            Optional<PublicIntegrationDetailDTO> detail = service.findBySlug("stripe");

            assertThat(detail).isPresent();
            assertThat(detail.get().tools()).hasSize(120);
            // The service asks for cap+1 rows precisely so truncation is OBSERVED here, not
            // inferred from a count that could disagree with the rows.
            assertThat(detail.get().toolsTruncated()).isFalse();
        }

        @Test
        @DisplayName("asks for one row more than it will show, so truncation is observed")
        void asksForOneExtraRow() {
            when(jdbcTemplate.queryForList(anyString(), anyArg()))
                .thenReturn(List.of(row("slack", "Slack", 1)));
            when(jdbcTemplate.queryForList(anyString(), anyArg(), anyArg())).thenReturn(List.of());

            service.findBySlug("slack");

            ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).queryForList(anyString(), args.capture(), args.capture());
            assertThat(args.getAllValues().get(1)).isEqualTo(121);
        }

        @Test
        @DisplayName("joins tool_names on a text cast, matching the VARCHAR foreign key")
        void toolQueryCastsTheJoinKey() {
            // api_tools.tool_name_id is VARCHAR(255) while tool_names.id is UUID: without
            // the cast the join fails at runtime, not at compile time.
            assertThat(PublicIntegrationService.toolsSql()).contains("tn.id::text = t.tool_name_id");
        }

        @Test
        @DisplayName("lists an endpoint whose name row is missing, rather than losing it")
        void toolQueryOuterJoinsTheName() {
            // The badge counts api_tools rows; an INNER JOIN here would drop any row
            // whose tool_name_id resolves to nothing, so a page would say "45 tools"
            // and list 44 with no error. Same LEFT JOIN idiom as WorkflowInspectorService.
            String sql = PublicIntegrationService.toolsSql();
            assertThat(sql).contains("LEFT JOIN catalog.tool_names");
            assertThat(sql).contains("COALESCE(tn.name, t.tool_slug)");
        }
    }
}
