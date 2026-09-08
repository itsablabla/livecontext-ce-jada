package com.apimarketplace.orchestrator.services.retention;

import com.apimarketplace.common.storage.repository.ExecutionLogStorageRetentionRepository;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger;
import com.apimarketplace.orchestrator.OrchestratorServiceApplication;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural pins for two defects that no behavioural test can reach, both of
 * which shipped green through 79 Mockito assertions.
 */
@DisplayName("Execution-log retention wiring")
class ExecutionLogRetentionWiringTest {

    /**
     * The boot-breaker.
     *
     * <p>Nine applications component-scan {@code com.apimarketplace.common.storage},
     * so {@link ExecutionLogStoragePurger} is discovered as a bean in all of them,
     * but each registers only {@code com.apimarketplace.common.storage.repository}
     * with {@code @EnableJpaRepositories}. The purger's repository dependency
     * therefore has to live in that exact package: anywhere else it is never
     * instantiated, the purger cannot be satisfied, and every one of those services
     * fails to start. Mockito never sees this, because it hands the purger a mock.
     */
    @Test
    @DisplayName("The retention repository sits in a package @EnableJpaRepositories actually scans")
    void repositoryIsInAScannedPackage() {
        // agent-service is pinned by its own sibling assertion: its application class
        // is not on this module's classpath, and the two services are the two that
        // instantiate the purger.
        String repositoryPackage = ExecutionLogStorageRetentionRepository.class.getPackageName();

        EnableJpaRepositories annotation =
                OrchestratorServiceApplication.class.getAnnotation(EnableJpaRepositories.class);
        assertNotNull(annotation, "orchestrator must declare @EnableJpaRepositories");

        boolean covered = Arrays.stream(annotation.basePackages())
                .anyMatch(base -> repositoryPackage.equals(base) || repositoryPackage.startsWith(base + "."));

        assertTrue(covered, () -> "ExecutionLogStorageRetentionRepository lives in " + repositoryPackage
                + ", which no @EnableJpaRepositories base package covers "
                + Arrays.toString(annotation.basePackages())
                + ". Every service scanning com.apimarketplace.common.storage would fail to boot.");
    }

    /**
     * The multi-replica defect.
     *
     * <p>The orchestrator runs several pods. Without a lock they all sweep at
     * 03:40, and two pods resolving the same payload batch both reach the storage
     * quota ledger, whose decrement is unconditional, so the loser debits bytes it
     * did not delete. Same convention as {@code FlagFlipAuditPurgeService}.
     */
    @Test
    @DisplayName("The scheduled sweep holds a ShedLock, so only one pod runs it")
    void scheduledSweepIsLocked() throws NoSuchMethodException {
        Method scheduled = ExecutionLogRetentionSweeper.class.getMethod("scheduledSweep");

        assertNotNull(scheduled.getAnnotation(Scheduled.class), "the sweep must stay scheduled");
        SchedulerLock lock = scheduled.getAnnotation(SchedulerLock.class);
        assertNotNull(lock, "scheduledSweep must carry @SchedulerLock: several pods run this service");
        assertTrue(!lock.name().isBlank(), "the lock needs a name to be a lock");
        assertTrue(!lock.lockAtMostFor().isBlank(),
                "without lockAtMostFor a pod that dies mid-sweep holds the lock forever");
    }
}
