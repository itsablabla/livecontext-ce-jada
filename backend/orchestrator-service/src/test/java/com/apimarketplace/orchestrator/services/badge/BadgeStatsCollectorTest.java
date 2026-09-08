package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.BadgeProfileDto;
import com.apimarketplace.publication.client.PublicationClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeStatsCollector")
class BadgeStatsCollectorTest {

    private static final String TENANT = "42";

    @Mock private PublicationClient publicationClient;
    @Mock private AuthClient authClient;
    @Mock private EntityManager entityManager;

    private BadgeStatsCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        collector = new BadgeStatsCollector(publicationClient, authClient);
        Field em = BadgeStatsCollector.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(collector, entityManager);
    }

    @Nested
    @DisplayName("source gating")
    class SourceGating {

        @Test
        @DisplayName("no source is touched when nothing is still locked")
        void emptyNeedSetTouchesNothing() {
            BadgeStats stats = collector.collect(TENANT, Set.of());

            assertThat(stats.get(BadgeMetric.WORKFLOWS_CREATED)).isZero();
            verifyNoInteractions(entityManager);
            verifyNoInteractions(publicationClient);
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("a blank tenant is a no-op rather than an unscoped query")
        void blankTenantTouchesNothing() {
            collector.collect("  ", Set.of(BadgeMetric.WORKFLOWS_CREATED));

            verifyNoInteractions(entityManager);
            verifyNoInteractions(publicationClient);
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("publication-service is not called when only a workflow badge is locked")
        void marketplaceSourceSkippedWhenNotNeeded() {
            // The authoring query throws (unstubbed EntityManager returns null,
            // and the collector swallows), which is fine: the assertion is about
            // which SOURCES get contacted, not about the values.
            collector.collect(TENANT, Set.of(BadgeMetric.WORKFLOWS_CREATED));

            verifyNoInteractions(publicationClient);
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("auth-service is not called when no cohort or tenure badge is locked")
        void identitySourceSkippedWhenNotNeeded() {
            when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT)).thenReturn(List.of());

            collector.collect(TENANT, Set.of(BadgeMetric.PUBLICATIONS_PUBLISHED));

            verifyNoInteractions(authClient);
        }
    }

    @Nested
    @DisplayName("marketplace metrics")
    class MarketplaceMetrics {

        @Test
        @DisplayName("only ACTIVE publications count as published, and only PUBLIC ones as shared")
        void countsActiveAndPublicSeparately() {
            when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT)).thenReturn(List.of(
                    Map.of("status", "ACTIVE", "visibility", "PUBLIC", "useCount", 10),
                    Map.of("status", "ACTIVE", "visibility", "UNLISTED", "useCount", 5),
                    Map.of("status", "PENDING_REVIEW", "visibility", "PUBLIC", "useCount", 1),
                    Map.of("status", "REJECTED", "visibility", "PRIVATE", "useCount", 0)));

            BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.PUBLICATIONS_PUBLISHED));

            assertThat(stats.get(BadgeMetric.PUBLICATIONS_PUBLISHED)).isEqualTo(2);
            assertThat(stats.get(BadgeMetric.PUBLICATIONS_PUBLIC)).isEqualTo(1);
        }

        @Test
        @DisplayName("installs count on retired publications too - the app was still used")
        void installsCountRegardlessOfStatus() {
            when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT)).thenReturn(List.of(
                    Map.of("status", "ACTIVE", "visibility", "PUBLIC", "useCount", 10),
                    Map.of("status", "INACTIVE", "visibility", "PUBLIC", "useCount", 500)));

            BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.PUBLICATION_USES));

            assertThat(stats.get(BadgeMetric.PUBLICATION_USES)).isEqualTo(510);
        }

        @Test
        @DisplayName("a publication-service outage leaves the metrics at zero instead of failing")
        void emptyPayloadLeavesMetricsAtZero() {
            when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT)).thenReturn(List.of());

            BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.PUBLICATION_USES));

            assertThat(stats.get(BadgeMetric.PUBLICATION_USES)).isZero();
            assertThat(stats.get(BadgeMetric.PUBLICATIONS_PUBLISHED)).isZero();
        }

        @Test
        @DisplayName("a row with missing fields is skipped, not counted as published")
        void malformedRowIsSkipped() {
            when(publicationClient.getPublicationsByPublisherPersonalScope(TENANT))
                    .thenReturn(java.util.Arrays.asList(Map.of("title", "no status here"), null));

            BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.PUBLICATIONS_PUBLISHED));

            assertThat(stats.get(BadgeMetric.PUBLICATIONS_PUBLISHED)).isZero();
            assertThat(stats.get(BadgeMetric.PUBLICATION_USES)).isZero();
        }
    }

    @Nested
    @DisplayName("join date parsing")
    class JoinDateParsing {

        @Test
        @DisplayName("the zone-less form auth-service sends is read as UTC")
        void zonelessLocalDateTimeIsUtc() {
            Instant parsed = BadgeStatsCollector.parseJoinedAt(
                    new BadgeProfileDto("42", "2026-03-04T10:15:30", true));

            assertThat(parsed).isEqualTo(
                    LocalDate.of(2026, 3, 4).atTime(10, 15, 30).toInstant(ZoneOffset.UTC));
        }

        @Test
        @DisplayName("an offset-carrying instant is still accepted")
        void instantFormIsAccepted() {
            Instant parsed = BadgeStatsCollector.parseJoinedAt(
                    new BadgeProfileDto("42", "2026-03-04T10:15:30Z", true));

            assertThat(parsed).isEqualTo(Instant.parse("2026-03-04T10:15:30Z"));
        }

        @Test
        @DisplayName("null, blank and unparseable values read as no date rather than throwing")
        void unusableValuesReadAsNull() {
            assertThat(BadgeStatsCollector.parseJoinedAt(null)).isNull();
            assertThat(BadgeStatsCollector.parseJoinedAt(new BadgeProfileDto("42", null, true))).isNull();
            assertThat(BadgeStatsCollector.parseJoinedAt(new BadgeProfileDto("42", "  ", true))).isNull();
            assertThat(BadgeStatsCollector.parseJoinedAt(new BadgeProfileDto("42", "not a date", true))).isNull();
        }

        @Test
        @DisplayName("an unreachable auth-service leaves the cohort metric at zero, unlocking nothing")
        void missingProfileLeavesCohortAtZero() {
            when(authClient.getBadgeProfile(TENANT)).thenReturn(null);

            BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.DAYS_UNTIL_JOIN_CUTOFF));

            assertThat(stats.get(BadgeMetric.DAYS_UNTIL_JOIN_CUTOFF)).isZero();
            assertThat(stats.get(BadgeMetric.MEMBER_DAYS)).isZero();
        }

        @Test
        @DisplayName("a known join date fills both the cohort and the tenure metric")
        void knownJoinDateFillsBothMetrics() {
            when(authClient.getBadgeProfile(TENANT))
                    .thenReturn(new BadgeProfileDto("42", "2026-06-01T00:00:00", true));

            BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.MEMBER_DAYS));

            assertThat(stats.get(BadgeMetric.DAYS_UNTIL_JOIN_CUTOFF)).isEqualTo(214); // to 2027-01-01
            assertThat(stats.get(BadgeMetric.MEMBER_DAYS)).isPositive();
        }
    }
}
