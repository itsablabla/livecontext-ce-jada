package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three properties of {@code recordRecall} that keep a READ from looking like
 * an EDIT.
 *
 * <p>Counting a recall must not move {@code updated_at}. If it does, the Memory
 * tab's "updated" line reports the last time an agent glanced at an entry instead
 * of the last time a person changed it, and the injection order
 * ({@code updated_at DESC}) reshuffles which pinned entries make the cut on every
 * read. Nothing about that is visible in a normal test run: the counter still
 * increments and every assertion about the returned row still passes.
 *
 * <p>Three separate things have to hold, and each is asserted here because each
 * has failed in this codebase before:
 * <ol>
 *   <li>it is a bulk {@code @Modifying} update, not an entity save, so
 *       {@code @PreUpdate} never fires;</li>
 *   <li>its SET clause touches the two counter columns and nothing else;</li>
 *   <li>{@code clearAutomatically} is on, so the caller's still-managed copy is
 *       evicted instead of being flushed back at commit - which would stamp
 *       {@code updated_at} after all, undoing (1).</li>
 * </ol>
 *
 * <p>Reflection rather than a Spring slice, following the precedent this module
 * already sets ({@code AgentExecutionRepositoryIncrementCountersQueryTest},
 * {@code AgentRepositoryResetQueryTest}): agent-service has no JPA test slice,
 * and the regression being guarded is a field appearing or disappearing from a
 * query string, which reflection reads exactly.
 */
@DisplayName("AgentMemoryRepository.recordRecall - a recall is not an edit")
class AgentMemoryRecallContractTest {

    private static Method recordRecall() {
        try {
            return AgentMemoryRepository.class.getMethod("recordRecall", UUID.class, Instant.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                "recordRecall's signature changed; the recall path is no longer the one under test", e);
        }
    }

    @Test
    @DisplayName("is a bulk @Modifying update, so the JPA lifecycle that stamps updated_at never runs")
    void isABulkModifyingUpdate() {
        assertThat(recordRecall().getAnnotation(Modifying.class))
            .as("without @Modifying this is not an update at all and Spring Data refuses it at bootstrap")
            .isNotNull();

        assertThat(recordRecall().getAnnotation(Query.class).value().trim())
            .as("an entity save here would fire @PreUpdate and stamp updated_at")
            .startsWith("UPDATE AgentMemoryEntity");
    }

    @Test
    @DisplayName("sets the recall counter and the recall timestamp, and nothing else")
    void setsOnlyTheCounterColumns() {
        String jpql = recordRecall().getAnnotation(Query.class).value();
        String setClause = jpql.substring(jpql.indexOf("SET"), jpql.indexOf("WHERE"));

        assertThat(setClause)
            .contains("m.recallCount = m.recallCount + 1")
            .contains("m.lastRecalledAt = :now");
        // The whole point: the column that must NOT be in this statement.
        assertThat(setClause)
            .as("a recall that writes updated_at makes the tab report reads as edits")
            .doesNotContain("updatedAt");
    }

    @Test
    @DisplayName("clears the persistence context, or the caller's managed copy is flushed back and stamps updated_at anyway")
    void clearsThePersistenceContext() {
        Modifying modifying = recordRecall().getAnnotation(Modifying.class);

        // This is the subtle half. The service still holds the managed entity and
        // updates its counter fields in memory so the caller sees current values.
        // Without the clear, that entity stays managed, the dirty check flushes an
        // entity UPDATE at commit, @PreUpdate fires, and updated_at is stamped -
        // reintroducing exactly the bug the bulk query exists to avoid, while every
        // assertion in the test above still passes.
        assertThat(modifying.clearAutomatically())
            .as("the caller's managed copy must be evicted, not flushed back")
            .isTrue();
        assertThat(modifying.flushAutomatically())
            .as("pending changes must be written BEFORE the clear discards them")
            .isTrue();
    }

    @Test
    @DisplayName("the injected index projects into a constructor JPQL can actually resolve")
    void theIndexProjectionResolves() throws Exception {
        String jpql;
        try {
            jpql = AgentMemoryRepository.class
                .getMethod("findIndexForInjectionStrict", String.class, UUID.class,
                    org.springframework.data.domain.Pageable.class)
                .getAnnotation(Query.class).value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("the injection index finder's signature changed", e);
        }

        // A JPQL constructor expression names the class as a STRING, so a wrong or
        // stale name is not a compile error - it fails when Hibernate parses the
        // query, which for a nested record written with the '$' binary name is
        // exactly the kind of thing that looks right and is not. Resolving the name
        // the query actually carries, and checking it has a matching constructor, is
        // the part a mocked repository can never tell us.
        String fqn = jpql.substring(jpql.indexOf("new ") + 4, jpql.indexOf('(', jpql.indexOf("new ")))
            .trim();
        Class<?> projected = Class.forName(fqn);

        assertThat(projected).isEqualTo(AgentMemoryRepository.IndexRow.class);
        assertThat(projected.getRecordComponents())
            .as("the constructor expression passes five columns, in this order")
            .hasSize(5);
        assertThat(projected.getConstructor(UUID.class, String.class, String.class,
            com.apimarketplace.agent.domain.AgentMemoryEntity.MemoryType.class, Boolean.class))
            .isNotNull();
    }
}
