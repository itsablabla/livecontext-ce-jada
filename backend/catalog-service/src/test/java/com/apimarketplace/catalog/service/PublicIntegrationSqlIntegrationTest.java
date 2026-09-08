package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.dto.PublicIntegrationDTO;
import com.apimarketplace.catalog.dto.PublicIntegrationDetailDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the public directory's hand-written SQL against a REAL PostgreSQL.
 *
 * <p><b>Why this exists next to {@link PublicIntegrationServiceTest}.</b> That
 * suite asserts the SQL as a STRING through a mocked {@code JdbcTemplate}, which
 * is the right place to pin the visibility gate but proves nothing about whether
 * Postgres accepts the statement. Three of the things this feature depends on are
 * invisible to a string assertion and were each a live bug or a live risk:
 *
 * <ul>
 *   <li>The predicates are concatenated into Java text blocks, which strip
 *       trailing whitespace, so {@code WHERE """ + PREDICATE} once produced the
 *       literal {@code WHEREa.is_active}. A syntax error, caught by nothing.</li>
 *   <li>{@code api_tools.tool_name_id} is VARCHAR while {@code tool_names.id} is
 *       UUID: the join needs an explicit cast, and without it Postgres refuses
 *       the query at execution time, not at compile time.</li>
 *   <li>The search escapes LIKE metacharacters with {@code ESCAPE '!'}. Whether
 *       a typed {@code %} really stops matching everything is a database
 *       behaviour, not a string one.</li>
 * </ul>
 *
 * <p>And the controller catches every exception to keep a marketing page up, so
 * a broken query here would surface as an empty section and a WARN line, never
 * as a failure anyone notices.
 *
 * <p>Skipped (not failed) when no Docker daemon is available, like its sibling
 * {@code ApiCatalogBundleSqlIntegrationTest}.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Public integration directory SQL - real-Postgres integration (Testcontainers)")
class PublicIntegrationSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("public_integrations_it")
            .withUsername("postgres")
            .withPassword("postgres");

    private JdbcTemplate jdbc;
    private PublicIntegrationService service;

    @BeforeAll
    void initSchema() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("schema-public-integrations-postgres.sql"));
        }
        jdbc = new JdbcTemplate(ds);
        service = new PublicIntegrationService(jdbc);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE catalog.api_tools, catalog.apis, catalog.tool_names, "
                + "catalog.api_usage_stats CASCADE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fixtures
    // ─────────────────────────────────────────────────────────────────────────

    /** Insert an integration and return its id. */
    private UUID api(String slug, String name, String description, String visibility,
                     boolean active, boolean deprecated) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO catalog.apis
                (id, created_by, api_name, api_slug, description, base_url, visibility,
                 auth_type, is_active, icon_slug, documentation, deprecated_at)
            VALUES (?, 'tenant-1', ?, ?, ?, 'https://example.test', ?, 'oauth2', ?, ?,
                    'https://docs.example.test', ?)
            """,
            id, name, slug, description, visibility, active, slug,
            deprecated ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
        return id;
    }

    /** A public, active, non-deprecated integration. */
    private UUID publicApi(String slug, String name, String description) {
        return api(slug, name, description, "public", true, false);
    }

    /** Insert an endpoint on an API, named through tool_names like production does. */
    private void tool(UUID apiId, String name, String description, String method,
                      boolean active, boolean deprecated) {
        UUID toolNameId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO catalog.tool_names (id, name, description, method, is_active) "
                + "VALUES (?, ?, ?, ?, true)",
            toolNameId, name, description, method);
        jdbc.update("""
            INSERT INTO catalog.api_tools
                (api_id, tool_slug, description, tool_name_id, method, endpoint, is_active, deprecated_at)
            VALUES (?, ?, ?, ?, ?, '/v1/thing', ?, ?)
            """,
            apiId, name, description, toolNameId.toString(), method, active,
            deprecated ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
    }

    private void tool(UUID apiId, String name) {
        tool(apiId, name, name + " does a thing", "POST", true, false);
    }

    private void runCount(String apiSlug, long count) {
        jdbc.update("INSERT INTO catalog.api_usage_stats (api_slug, run_count) VALUES (?, ?)",
            apiSlug, count);
    }

    private List<String> listedSlugs(String query) {
        return service.list(0, 50, query).stream().map(PublicIntegrationDTO::slug).toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // the queries actually run
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every query is valid SQL against the real schema")
    void queriesExecute() {
        UUID slack = publicApi("slack", "Slack", "Post messages and manage channels.");
        tool(slack, "send_message");

        // The failure this catches is not a wrong result, it is a thrown
        // exception the production controller would swallow into an empty page.
        assertThat(service.list(0, 20, null)).hasSize(1);
        assertThat(service.list(0, 20, "slack")).hasSize(1);
        assertThat(service.count(null)).isEqualTo(1);
        assertThat(service.count("slack")).isEqualTo(1);
        assertThat(service.findBySlug("slack")).isPresent();
    }

    @Test
    @DisplayName("ranks by run count, then by slug, with never-run integrations last")
    void ranksByUsageThenSlug() {
        UUID github = publicApi("github", "GitHub", "Issues, pull requests and repositories.");
        UUID slack = publicApi("slack", "Slack", "Post messages and manage channels.");
        UUID acme = publicApi("acme", "Acme", "Does acme things.");
        UUID zeta = publicApi("zeta", "Zeta", "Does zeta things.");
        tool(github, "create_issue");
        tool(slack, "send_message");
        tool(acme, "do_thing");
        tool(zeta, "do_thing");
        runCount("slack", 900);
        runCount("github", 50);

        // Run ones first by count; the rest alphabetical by SLUG, which is what
        // makes the order total and therefore the paging stable.
        assertThat(listedSlugs(null)).containsExactly("slack", "github", "acme", "zeta");
    }

    @Test
    @DisplayName("pages without repeating or skipping an integration")
    void pagesStably() {
        for (int i = 0; i < 7; i++) {
            UUID id = publicApi("api-" + i, "API " + i, "Description " + i);
            tool(id, "op_" + i);
        }

        List<String> firstPage = service.list(0, 3, null).stream().map(PublicIntegrationDTO::slug).toList();
        List<String> secondPage = service.list(1, 3, null).stream().map(PublicIntegrationDTO::slug).toList();
        List<String> thirdPage = service.list(2, 3, null).stream().map(PublicIntegrationDTO::slug).toList();

        assertThat(firstPage).containsExactly("api-0", "api-1", "api-2");
        assertThat(secondPage).containsExactly("api-3", "api-4", "api-5");
        assertThat(thirdPage).containsExactly("api-6");
        assertThat(service.count(null)).isEqualTo(7);
    }

    @Test
    @DisplayName("never serves a private, inactive or retired integration")
    void hidesWhatIsNotPublic() {
        UUID visible = publicApi("slack", "Slack", "Post messages.");
        UUID priv = api("secret", "Someone's Private API", "Internal.", "private", true, false);
        UUID inactive = api("inactive", "Inactive", "Off.", "public", false, false);
        UUID retired = api("retired", "Retired", "Gone.", "public", true, true);
        tool(visible, "send_message");
        tool(priv, "do_secret_thing");
        tool(inactive, "op");
        tool(retired, "op");

        assertThat(listedSlugs(null)).containsExactly("slack");
        assertThat(service.count(null)).isEqualTo(1);
        // And the detail read is gated too: a directory that hides a private API
        // but serves it at /integrations/{slug} hides nothing.
        assertThat(service.findBySlug("secret")).isEmpty();
        assertThat(service.findBySlug("inactive")).isEmpty();
        assertThat(service.findBySlug("retired")).isEmpty();
    }

    @Test
    @DisplayName("never serves an integration whose endpoints are all gone")
    void hidesToollessIntegrations() {
        UUID empty = publicApi("empty", "Empty", "Has no endpoint at all.");
        UUID allInactive = publicApi("dormant", "Dormant", "Every endpoint disabled.");
        tool(allInactive, "op", "does a thing", "GET", false, false);
        UUID allRetired = publicApi("sunset", "Sunset", "Every endpoint retired.");
        tool(allRetired, "op", "does a thing", "GET", true, true);
        UUID live = publicApi("slack", "Slack", "Post messages.");
        tool(live, "send_message");

        // A page for an integration with no usable endpoint promises something
        // the product cannot do.
        assertThat(listedSlugs(null)).containsExactly("slack");
        assertThat(service.findBySlug("empty")).isEmpty();
        assertThat(service.findBySlug("dormant")).isEmpty();
        assertThat(service.findBySlug("sunset")).isEmpty();
    }

    @Test
    @DisplayName("never serves an integration that has no slug, and never counts one either")
    void hidesSluglessIntegrations() {
        UUID slack = publicApi("slack", "Slack", "Post messages.");
        tool(slack, "send_message");
        UUID orphan = publicApi("temp", "No Slug Yet", "Predates the slug backfill.");
        tool(orphan, "op");
        jdbc.update("UPDATE catalog.apis SET api_slug = NULL WHERE id = ?", orphan);

        // api_slug is nullable. Such a row has no URL, so the frontend mapper drops it:
        // counting it would inflate the headline while producing no card and no page.
        assertThat(listedSlugs(null)).containsExactly("slack");
        assertThat(service.count(null)).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps two integrations that happen to share a slug as two")
    void doesNotCollapseSharedSlugs() {
        // api_slug carries no UNIQUE constraint. Grouping the list on the slug while
        // the count grouped on the id made such a pair render as ONE card with their
        // endpoint counts summed, while the headline still said two.
        // Identical in EVERY column the old slug-based key listed (name, description,
        // icon_slug and auth_type, the last two fixed by publicApi). A pair that
        // differed in any of them would be separated by that key too, and this test
        // would pass on the bug it describes.
        UUID first = publicApi("shared", "Same Name", "The same description.");
        UUID second = publicApi("shared", "Same Name", "The same description.");
        tool(first, "op_a");
        tool(second, "op_b");
        tool(second, "op_c");

        assertThat(service.list(0, 50, null))
            .extracting(PublicIntegrationDTO::name, PublicIntegrationDTO::toolCount)
            .containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple("Same Name", 1),
                org.assertj.core.api.Assertions.tuple("Same Name", 2));
        assertThat(service.count(null)).isEqualTo(2);
    }

    @Test
    @DisplayName("still lists an endpoint whose name row is missing")
    void keepsEndpointsWithNoNameRow() {
        UUID slack = publicApi("slack", "Slack", "Post messages.");
        tool(slack, "send_message");
        // An endpoint pointing at a tool_names row that is not there. An INNER JOIN
        // would drop it, so the badge would say 2 tools and the page would list 1.
        jdbc.update("""
            INSERT INTO catalog.api_tools
                (api_id, tool_slug, description, tool_name_id, method, endpoint, is_active)
            VALUES (?, 'slack-orphaned-op', 'Its name row is gone', ?, 'GET', '/v1/thing', true)
            """, slack, UUID.randomUUID().toString());

        Optional<PublicIntegrationDetailDTO> detail = service.findBySlug("slack");

        assertThat(detail).isPresent();
        assertThat(detail.get().integration().toolCount()).isEqualTo(2);
        // Falls back to the tool slug, so the row is still readable rather than blank.
        assertThat(detail.get().tools()).extracting("name")
            .containsExactly("send_message", "slack-orphaned-op");
    }

    @Test
    @DisplayName("counts each integration once, not once per endpoint")
    void countsIntegrationsNotRows() {
        UUID slack = publicApi("slack", "Slack", "Post messages.");
        for (int i = 0; i < 12; i++) {
            tool(slack, "op_" + i);
        }
        UUID github = publicApi("github", "GitHub", "Issues.");
        tool(github, "create_issue");

        // The tool join multiplies an API by its endpoint count: a plain
        // COUNT(*) here would report 13 integrations.
        assertThat(service.count(null)).isEqualTo(2);
        // Neither has been run, so the order is the alphabetical slug tie-break.
        assertThat(service.list(0, 50, null))
            .extracting(PublicIntegrationDTO::slug, PublicIntegrationDTO::toolCount)
            .containsExactly(
                org.assertj.core.api.Assertions.tuple("github", 1),
                org.assertj.core.api.Assertions.tuple("slack", 12));
    }

    @Test
    @DisplayName("searches the name, the slug and the description, case-insensitively")
    void searchesEveryUserVisibleField() {
        UUID slack = publicApi("slack", "Slack", "Post messages to channels.");
        UUID github = publicApi("github", "GitHub", "Issues and pull requests.");
        UUID drive = publicApi("google-drive", "Google Drive", "Store and share files.");
        tool(slack, "send_message");
        tool(github, "create_issue");
        tool(drive, "upload_file");

        assertThat(listedSlugs("SLA")).containsExactly("slack");            // name
        assertThat(listedSlugs("google-dr")).containsExactly("google-drive"); // slug
        assertThat(listedSlugs("pull requests")).containsExactly("github");  // description
        assertThat(listedSlugs("nothing-matches-this")).isEmpty();
        assertThat(service.count("SLA")).isEqualTo(1);
    }

    @Test
    @DisplayName("a typed % or _ is a literal character, not a wildcard")
    void escapesLikeMetacharacters() {
        UUID discount = publicApi("discounts", "Discounts", "Apply a 50% discount to an order.");
        UUID slack = publicApi("slack", "Slack", "Post messages.");
        tool(discount, "apply_discount");
        tool(slack, "send_message");

        // Escaped, "%" is a percent sign: it finds the ONE row whose description
        // contains one, and not the other. Unescaped it is a wildcard, so this
        // same search would return the whole catalog and the search box would
        // silently stop searching.
        assertThat(listedSlugs("%")).containsExactly("discounts");
        assertThat(listedSlugs("50%")).containsExactly("discounts");
        // Same for "_", which unescaped matches any single character: "s_ack"
        // would otherwise find Slack.
        assertThat(listedSlugs("s_ack")).isEmpty();
        assertThat(listedSlugs("_")).isEmpty();
        assertThat(listedSlugs("sl_ck")).isEmpty();
    }

    @Test
    @DisplayName("a quote in the search term is data, not SQL")
    void searchTermIsBound() {
        UUID slack = publicApi("slack", "Slack", "Post messages.");
        tool(slack, "send_message");

        // Bound as a parameter, so this is a search for a funny string rather
        // than a statement. It must answer nothing and must not throw.
        assertThat(listedSlugs("'; DROP TABLE catalog.apis; --")).isEmpty();
        assertThat(service.count(null)).isEqualTo(1);
    }

    @Test
    @DisplayName("reads one integration with its endpoints, alphabetically")
    void readsDetailWithEndpoints() {
        UUID slack = publicApi("slack", "Slack", "Post messages and manage channels.");
        tool(slack, "send_message", "Post a message to a channel", "POST", true, false);
        tool(slack, "list_channels", "List the channels you can see", "GET", true, false);
        tool(slack, "archive_channel", "Archive a channel", "POST", true, false);
        tool(slack, "hidden_op", "Disabled", "GET", false, false);

        Optional<PublicIntegrationDetailDTO> detail = service.findBySlug("slack");

        assertThat(detail).isPresent();
        assertThat(detail.get().integration().name()).isEqualTo("Slack");
        assertThat(detail.get().integration().toolCount()).isEqualTo(3);
        assertThat(detail.get().documentation()).isEqualTo("https://docs.example.test");
        // The join to tool_names is what supplies these names, through the
        // VARCHAR-to-UUID cast. Alphabetical, and the inactive one is absent.
        assertThat(detail.get().tools()).extracting("name")
            .containsExactly("archive_channel", "list_channels", "send_message");
        assertThat(detail.get().tools()).extracting("method")
            .containsExactly("POST", "GET", "POST");
        assertThat(detail.get().toolsTruncated()).isFalse();
    }

    @Test
    @DisplayName("caps a very large endpoint list and reports the truncation")
    void truncatesLargeEndpointLists() {
        UUID stripe = publicApi("stripe", "Stripe", "Payments.");
        for (int i = 0; i < 130; i++) {
            tool(stripe, String.format("op_%03d", i));
        }

        Optional<PublicIntegrationDetailDTO> detail = service.findBySlug("stripe");

        assertThat(detail).isPresent();
        assertThat(detail.get().tools()).hasSize(120);
        assertThat(detail.get().toolsTruncated()).isTrue();
        // The head count is the REAL total, so the page can say "120 of 130".
        assertThat(detail.get().integration().toolCount()).isEqualTo(130);
    }

    @Test
    @DisplayName("falls back to the catalog glyph when an integration declares no icon")
    void defaultsTheIconSlug() {
        UUID id = api("plain", "Plain", "No icon.", "public", true, false);
        jdbc.update("UPDATE catalog.apis SET icon_slug = NULL WHERE id = ?", id);
        tool(id, "op");

        assertThat(service.list(0, 20, null).get(0).iconSlug()).isEqualTo("mcp");
    }
}
