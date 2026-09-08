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
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link ApiCatalogBundleRepository#findActiveMetadata()} against real
 * JPA, because a mocked repository cannot tell a valid JPQL from a broken one.
 *
 * <p>The projection exists so the conditional-GET path can answer "has the
 * bundle changed?" without dragging the ~24 MB {@code payload_gz} into heap,
 * and so a CE-side row (payload NULL) is still recognised as not servable. Both
 * of those are properties of the generated query, so they are asserted here
 * rather than in the mock-based tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bundlemeta;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS catalog",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("bundle-meta-slice")
@DisplayName("ApiCatalogBundleRepository.findActiveMetadata - payload-free identity of the active bundle")
class ApiCatalogBundleMetadataProjectionTest {

    @SpringBootConfiguration
    @Profile("bundle-meta-slice")
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ApiCatalogBundleEntity.class)
    @EnableJpaRepositories(
            basePackageClasses = ApiCatalogBundleRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = ApiCatalogBundleRepository.class))
    static class SliceConfig {
    }

    @Autowired private ApiCatalogBundleRepository repo;

    private ApiCatalogBundleEntity bundle(long version, String checksum, byte[] payload, boolean active) {
        return bundle(version, checksum, payload, active, null);
    }

    private ApiCatalogBundleEntity bundle(long version, String checksum, byte[] payload, boolean active,
                                          String generationPrices) {
        ApiCatalogBundleEntity e = new ApiCatalogBundleEntity();
        e.setVersion(version);
        e.setSchemaVersion(1);
        e.setChecksum(checksum);
        e.setSignature("sig");
        e.setSigningKeyId("k1");
        e.setIssuer("cloud");
        e.setApiCount(1);
        e.setToolCount(1);
        e.setRawBytesSize(10);
        e.setPayloadGz(payload);
        e.setGenerationPrices(generationPrices);
        e.setActive(active);
        e.setImportedAt(Instant.now());
        return e;
    }

    @Test
    @DisplayName("Returns the active row's checksum, marked servable when a payload is stored")
    void readsTheActiveRowIdentity() {
        repo.save(bundle(1L, "old", new byte[]{1}, false));
        repo.save(bundle(2L, "current", new byte[]{1, 2, 3}, true));

        List<ApiCatalogBundleRepository.ActiveBundleMeta> found = repo.findActiveMetadata();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getChecksum()).isEqualTo("current");
        assertThat(found.get(0).getServable()).isEqualTo(1);
    }

    @Test
    @DisplayName("A row whose payload was never stored reports servable=0 - a CE applied row is not an origin")
    void nullPayloadIsNotServable() {
        repo.save(bundle(3L, "applied-here", null, true));

        List<ApiCatalogBundleRepository.ActiveBundleMeta> found = repo.findActiveMetadata();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getServable()).isZero();
    }

    @Test
    @DisplayName("No active row yields an empty list, never an exception")
    void noActiveRow() {
        repo.save(bundle(4L, "inactive", new byte[]{9}, false));

        assertThat(repo.findActiveMetadata()).isEmpty();
    }

    @Test
    @DisplayName("findAllSummariesNewestFirst returns every row, newest first, with no payload column read")
    void listsSummariesNewestFirst() {
        repo.save(bundle(10L, "old", new byte[]{1}, false));
        repo.save(bundle(30L, "current", new byte[]{1, 2, 3}, true));
        repo.save(bundle(20L, "mid", null, false));

        List<ApiCatalogBundleRepository.BundleSummary> all = repo.findAllSummariesNewestFirst();

        assertThat(all).extracting(ApiCatalogBundleRepository.BundleSummary::getVersion)
                .containsExactly(30L, 20L, 10L);
        assertThat(all.get(0).getActive()).isTrue();
        assertThat(all.get(0).getChecksum()).isEqualTo("current");
        assertThat(all.get(1).getActive()).isFalse();
        // A row with no payload still lists: the admin view never renders bytes.
        assertThat(all.get(1).getVersion()).isEqualTo(20L);
    }

    @Test
    @DisplayName("An empty table lists nothing rather than failing")
    void listsNothingWhenEmpty() {
        assertThat(repo.findAllSummariesNewestFirst()).isEmpty();
    }

    @Test
    @DisplayName("pricesStored reports whether the row can serve a price re-offer without the payload")
    void reportsWhetherPricesAreStored() {
        // Drives the real CASE expression: this flag is what decides whether an
        // install may go conditional, and getting it wrong stops price
        // re-offering silently rather than loudly.
        repo.save(bundle(40L, "no-prices", new byte[]{1}, true, null));

        assertThat(repo.findActiveMetadata().get(0).getPricesStored()).isZero();

        repo.deleteAll();
        repo.save(bundle(41L, "with-prices", new byte[]{1}, true, "[{\"toolSlug\":\"flux\"}]"));

        assertThat(repo.findActiveMetadata().get(0).getPricesStored()).isEqualTo(1);
    }
}
