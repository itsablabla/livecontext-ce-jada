package com.apimarketplace.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MultiTargetMigrator")
class MultiTargetMigratorTest {

    private static Flyway flywayOn(DataSource ds) {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(ds);
        return flyway;
    }

    @Test
    @DisplayName("Parses comma or whitespace separated URLs, trims, drops blanks and duplicates, keeps order")
    void parsesTargets() {
        assertThat(MultiTargetMigrator.parseTargets(null)).isEmpty();
        assertThat(MultiTargetMigrator.parseTargets("  ")).isEmpty();
        assertThat(MultiTargetMigrator.parseTargets("jdbc:a, jdbc:b\n jdbc:a ,,jdbc:c"))
                .containsExactly("jdbc:a", "jdbc:b", "jdbc:c");
    }

    /** Today's behaviour must survive untouched when no extra target is configured. */
    @Test
    @DisplayName("With no extra target only the primary is prepared and migrated, and no Flyway is built")
    void noExtraTargetsIsTodaysBehaviour() {
        DataSource primaryDs = mock(DataSource.class);
        Flyway primary = mock(Flyway.class);
        List<DataSource> prepared = new ArrayList<>();
        List<Flyway> migrated = new ArrayList<>();
        List<String> built = new ArrayList<>();
        MultiTargetMigrator migrator = new MultiTargetMigrator(
                url -> { built.add(url); return flywayOn(mock(DataSource.class)); }, prepared::add, migrated::add);

        migrator.run(primary, primaryDs, List.of());

        assertThat(prepared).containsExactly(primaryDs);
        assertThat(migrated).containsExactly(primary);
        assertThat(built).isEmpty();
    }

    @Test
    @DisplayName("Primary first, then each extra target in order, each prepared before it is migrated")
    void primaryThenTargetsInOrder() {
        DataSource primaryDs = mock(DataSource.class);
        Flyway primary = mock(Flyway.class);
        DataSource dsA = mock(DataSource.class);
        DataSource dsB = mock(DataSource.class);
        Flyway a = flywayOn(dsA);
        Flyway b = flywayOn(dsB);
        List<Object> events = new ArrayList<>();
        MultiTargetMigrator migrator = new MultiTargetMigrator(
                url -> url.endsWith("a") ? a : b,
                ds -> events.add("prepare:" + name(ds, primaryDs, dsA, dsB)),
                f -> events.add("migrate:" + name(f, primary, a, b)));

        migrator.run(primary, primaryDs, List.of("jdbc:a", "jdbc:b"));

        assertThat(events).containsExactly(
                "prepare:primary", "migrate:primary",
                "prepare:a", "migrate:a",
                "prepare:b", "migrate:b");
    }

    /**
     * Fail fast: two databases at different versions is the state a helm --atomic rollout
     * must roll back from, so the second target must not be attempted after the first threw.
     */
    @Test
    @DisplayName("The first failing target stops the run; later targets are not touched")
    void failsFast() {
        Flyway primary = mock(Flyway.class);
        Flyway a = flywayOn(mock(DataSource.class));
        Flyway b = flywayOn(mock(DataSource.class));
        MultiTargetMigrator migrator = new MultiTargetMigrator(
                url -> url.endsWith("a") ? a : b,
                ds -> { },
                f -> { if (f == a) throw new IllegalStateException("checksum mismatch on a"); });

        assertThatThrownBy(() -> migrator.run(primary, mock(DataSource.class), List.of("jdbc:a", "jdbc:b")))
                .hasMessageContaining("checksum mismatch on a");
        verify(b, never()).getConfiguration();
    }

    @Test
    @DisplayName("Credentials embedded in a URL are never logged")
    void redactsCredentials() {
        assertThat(MultiTargetMigrator.redact("jdbc:postgresql://h/db?user=lc&password=s3cret&x=1"))
                .isEqualTo("jdbc:postgresql://h/db?user=***&password=***&x=1");
    }

    private static String name(Object o, Object primary, Object a, Object b) {
        return o == primary ? "primary" : o == a ? "a" : o == b ? "b" : "?";
    }
}
