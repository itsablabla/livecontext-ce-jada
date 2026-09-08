package com.apimarketplace.migration;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs the ONE linear migration history against several databases, in order, failing fast.
 *
 * <p><b>Why.</b> Every service owns a schema, and the scaling path for user tables and
 * vectors is to move {@code datasource.*} to its own database (a PgBouncer alias change and a
 * data copy; see docs/ops/SCHEMA_MOVE_RUNBOOK.md). Flyway, however, keeps a single history
 * for all ten schemas and this service knew exactly one URL. Splitting the 460+ migration
 * files by schema is not on the table: many touch several schemas and their order is the
 * contract. The model that works without rewriting history is <i>every database carries
 * every schema, and the same history runs on each</i>: on the database that holds
 * {@code datasource.*} the orchestrator tables exist but stay empty, and vice versa. A
 * migration that alters an empty table is a no-op; one that moves data between schemas
 * moves nothing on the side that has none. The primary URL stays first, the extra targets
 * follow, and the first failure stops the run so a helm {@code --atomic} rollout rolls back
 * rather than leaving the databases at different versions.
 *
 * <p>Targets are additional JDBC URLs (comma or whitespace separated), reached with the same
 * credentials as the primary. Each should carry the same
 * {@code ?options=-c%20lc.migration.source_timezone%3DUTC} suffix as the primary URL; the
 * {@code ALTER DATABASE} hook runs on every target as well, as belt and braces (see
 * {@link MigrationServiceApplication#ensureMigrationSourceTimezoneGuc}).
 *
 * <p>Empty targets = today's behaviour, exactly: one database, nothing else runs.
 */
final class MultiTargetMigrator {

    private static final Logger log = LoggerFactory.getLogger(MultiTargetMigrator.class);

    private final Function<String, Flyway> flywayForUrl;
    private final Consumer<DataSource> beforeEachDatabase;
    private final Consumer<Flyway> migrate;

    /**
     * @param flywayForUrl       builds a Flyway for an extra target, configured like the primary
     *                           (same locations, schemas, baseline, history table) but pointing
     *                           at the given URL
     * @param beforeEachDatabase the per-database preparation hook (the timezone GUC)
     * @param migrate            what to run on each Flyway: repair then migrate
     */
    MultiTargetMigrator(Function<String, Flyway> flywayForUrl,
                        Consumer<DataSource> beforeEachDatabase,
                        Consumer<Flyway> migrate) {
        this.flywayForUrl = flywayForUrl;
        this.beforeEachDatabase = beforeEachDatabase;
        this.migrate = migrate;
    }

    /**
     * Parses the {@code migration.extra-target-urls} property: comma or whitespace separated,
     * trimmed, blanks dropped, duplicates dropped in first-seen order.
     */
    static List<String> parseTargets(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String part : raw.split("[,\\s]+")) {
            if (!part.isBlank()) {
                urls.add(part.trim());
            }
        }
        return new ArrayList<>(urls);
    }

    /** Primary first, then each extra target in order. The first exception propagates. */
    void run(Flyway primary, DataSource primaryDataSource, List<String> extraTargetUrls) {
        beforeEachDatabase.accept(primaryDataSource);
        migrate.accept(primary);
        if (extraTargetUrls.isEmpty()) {
            return;
        }
        log.info("Migration: primary done, {} extra target(s) follow (same history, same credentials)",
                extraTargetUrls.size());
        int index = 0;
        for (String url : extraTargetUrls) {
            index++;
            log.info("Migration: extra target {}/{}: {}", index, extraTargetUrls.size(), redact(url));
            Flyway flyway = flywayForUrl.apply(url);
            beforeEachDatabase.accept(flyway.getConfiguration().getDataSource());
            migrate.accept(flyway);
        }
        log.info("Migration: all {} database(s) at the same version", extraTargetUrls.size() + 1);
    }

    /** A JDBC URL may embed credentials; never log them. */
    static String redact(String url) {
        return url.replaceAll("(?i)(password|user)=([^&]*)", "$1=***");
    }
}
