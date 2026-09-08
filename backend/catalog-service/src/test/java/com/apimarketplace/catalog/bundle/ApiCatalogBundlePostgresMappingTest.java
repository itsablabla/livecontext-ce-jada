package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes and reads {@link ApiCatalogBundleEntity} against a REAL PostgreSQL,
 * because H2 cannot see the failure this guards.
 *
 * <p>The generation-prices column was first mapped as {@code jsonb} on a
 * {@code String} without {@code @JdbcTypeCode(SqlTypes.JSON)}. Hibernate then
 * binds the parameter as varchar, and Postgres refuses a varchar-to-jsonb
 * assignment (42804) - which would have failed EVERY write of this table, on the
 * cloud and on every CE, for a column nothing even queries into. H2 in
 * PostgreSQL mode accepts that same mapping, so the whole H2-backed suite stayed
 * green. The column is now {@code text}; this test is what keeps the mapping
 * honest if anyone changes it back.
 *
 * <p><b>It must not be able to skip itself in CI.</b> The runners have no Docker
 * socket, so a container-only test would skip there and report green having
 * verified nothing - the exact green-by-absence this class exists to remove. It
 * therefore prefers the Postgres CI hands over through
 * {@code CATALOG_TEST_PG_URL} (same contract as {@code MIGRATION_TEST_PG_URL} in
 * migration-service) and only falls back to a container on a developer machine.
 *
 * <p><b>Known limit.</b> Hibernate creates the table from the entity here, so
 * this pins the ENTITY mapping, not entity-versus-V476 agreement: flipping the
 * migration alone back to {@code jsonb} would still pass. Pinning that needs the
 * migration replayed, which is migration-service's job.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("bundle-pg-slice")
@DisplayName("ApiCatalogBundleEntity - round trip on real PostgreSQL")
class ApiCatalogBundlePostgresMappingTest {

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void openPostgres() {
        String ciUrl = System.getenv("CATALOG_TEST_PG_URL");
        if (ciUrl != null && !ciUrl.isBlank()) {
            // CI handed us one: the test MUST run, no assumption, no skip.
            url = ciUrl;
            user = envOr("CATALOG_TEST_PG_USER", "postgres");
            password = envOr("CATALOG_TEST_PG_PASSWORD", "postgres");
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "no CATALOG_TEST_PG_URL and no Docker: nothing to run against");
        container = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("bundle_mapping_it")
                .withUsername("postgres")
                .withPassword("postgres");
        container.start();
        url = container.getJdbcUrl();
        user = container.getUsername();
        password = container.getPassword();
    }

    @AfterAll
    static void closePostgres() {
        if (container != null) {
            container.stop();
        }
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    @SpringBootConfiguration
    @Profile("bundle-pg-slice")
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ApiCatalogBundleEntity.class)
    @EnableJpaRepositories(
            basePackageClasses = ApiCatalogBundleRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = ApiCatalogBundleRepository.class))
    static class SliceConfig {
    }

    @Autowired private ApiCatalogBundleRepository repo;

    private static ApiCatalogBundleEntity row(long version, String prices) {
        ApiCatalogBundleEntity e = new ApiCatalogBundleEntity();
        e.setVersion(version);
        e.setSchemaVersion(1);
        e.setChecksum("c".repeat(64));
        e.setSignature("sig");
        e.setSigningKeyId("k1");
        e.setIssuer("cloud");
        e.setApiCount(1);
        e.setToolCount(1);
        e.setRawBytesSize(10);
        e.setGenerationPrices(prices);
        e.setActive(true);
        e.setImportedAt(Instant.now());
        return e;
    }

    @Test
    @DisplayName("Stored prices survive a write and a read on Postgres, which is where the varchar binding would fail")
    void storedPricesRoundTrip() {
        repo.saveAndFlush(row(1L, "[{\"toolSlug\":\"flux-generate\",\"credits\":12}]"));

        assertThat(repo.findFirstByActiveTrue().orElseThrow().getGenerationPrices())
                .contains("flux-generate");
    }

    @Test
    @DisplayName("An empty capture and a NULL are both writable and stay distinguishable")
    void emptyAndNullAreBothWritable() {
        // The distinction the conditional-GET path depends on: "[]" means
        // captured-and-says-nothing, NULL means never captured.
        repo.saveAndFlush(row(2L, "[]"));
        repo.saveAndFlush(row(3L, null));

        List<ApiCatalogBundleRepository.BundleSummary> all = repo.findAllSummariesNewestFirst();
        assertThat(all).hasSize(2);
        assertThat(repo.findByVersion(2L).orElseThrow().getGenerationPrices()).isEqualTo("[]");
        assertThat(repo.findByVersion(3L).orElseThrow().getGenerationPrices()).isNull();
    }

    @Test
    @DisplayName("The payload-free projection reports pricesStored correctly on Postgres too")
    void projectionFlagsOnPostgres() {
        repo.saveAndFlush(row(4L, "[]"));

        ApiCatalogBundleRepository.ActiveBundleMeta meta = repo.findActiveMetadata().get(0);

        assertThat(meta.getPricesStored()).isEqualTo(1);
        assertThat(meta.getChecksum()).isEqualTo("c".repeat(64));
    }
}
