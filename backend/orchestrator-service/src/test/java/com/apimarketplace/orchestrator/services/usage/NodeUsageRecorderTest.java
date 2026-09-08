package com.apimarketplace.orchestrator.services.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the in-memory half of the node-usage ledger (V461).
 *
 * <p>What matters here is not that a number lands in a table but that a launch is
 * counted exactly once, reaches the right owner, and can never fail a run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NodeUsageRecorder")
class NodeUsageRecorderTest {

    @Mock private NodeUsageRepository repository;
    @Mock private ToolUsagePublisher publisher;

    private NodeUsageRecorder recorder;

    @BeforeEach
    void setUp() {
        when(publisher.publish(anyMap())).thenReturn(true);
        recorder = new NodeUsageRecorder(repository, publisher, true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> capturedNodeDeltas() {
        ArgumentCaptor<Map<String, Long>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repository).addCounts(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> capturedToolDeltas() {
        ArgumentCaptor<Map<String, Long>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("sends node keys to the local ledger with the prefix stripped")
    void nodeKeysGoToTheRepositoryWithoutTheirPrefix() {
        recorder.record("node:agent");
        recorder.record("node:agent");
        recorder.record("node:core_decision");

        recorder.flush();

        assertEquals(Map.of("agent", 2L, "core_decision", 1L), capturedNodeDeltas());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("sends tool keys to catalog, never to the local ledger")
    void toolKeysGoToCatalogOnly() {
        recorder.record("tool:elevenlabs-text-to-speech");
        recorder.record("tool:elevenlabs-text-to-speech");
        recorder.record("tool:slack/slack-post-message");

        recorder.flush();

        assertEquals(
                Map.of("elevenlabs-text-to-speech", 2L, "slack/slack-post-message", 1L),
                capturedToolDeltas());
        verify(repository, never()).addCounts(anyMap());
    }

    @Test
    @DisplayName("a flushed window is not counted again by the next flush")
    void windowIsDrainedOnFlush() {
        recorder.record("node:agent");
        recorder.flush();
        recorder.flush();

        // Second flush found a zeroed counter: one write, not two.
        verify(repository, times(1)).addCounts(anyMap());
    }

    @Test
    @DisplayName("keeps a window the local ledger refused, and carries it into the next flush")
    void failedNodeWriteIsRetriedInTheNextWindow() {
        doThrow(new RuntimeException("db down")).when(repository).addCounts(anyMap());
        recorder.record("node:agent");
        recorder.record("node:agent");
        recorder.flush();

        // Ledger is back, and one more launch happened meanwhile.
        org.mockito.Mockito.reset(repository);
        recorder.record("node:agent");
        recorder.flush();

        assertEquals(Map.of("agent", 3L), capturedNodeDeltas());
    }

    @Test
    @DisplayName("keeps a window catalog refused, and carries it into the next flush")
    void failedToolPublishIsRetriedInTheNextWindow() {
        when(publisher.publish(anyMap())).thenReturn(false);
        recorder.record("tool:stripe-create-charge");
        recorder.flush();

        org.mockito.Mockito.reset(publisher);
        when(publisher.publish(anyMap())).thenReturn(true);
        recorder.record("tool:stripe-create-charge");
        recorder.flush();

        assertEquals(Map.of("stripe-create-charge", 2L), capturedToolDeltas());
    }

    @Test
    @DisplayName("ignores a null, blank, unprefixed or over-long key instead of storing it")
    void rejectsKeysItCannotRoute() {
        recorder.record(null);
        recorder.record("");
        recorder.record("   ");
        recorder.record("agent");                       // no prefix: nothing would know where it belongs
        recorder.record("tool:" + "x".repeat(NodeUsageRecorder.MAX_KEY_LENGTH));

        recorder.flush();

        verify(repository, never()).addCounts(anyMap());
        verify(publisher, never()).publish(anyMap());
    }

    @Test
    @DisplayName("stops tracking NEW keys past the ceiling but keeps counting the ones it has")
    void boundsTheNumberOfDistinctKeys() {
        for (int i = 0; i < NodeUsageRecorder.MAX_TRACKED_KEYS + 500; i++) {
            recorder.record("tool:endpoint-" + i);
        }
        recorder.record("tool:endpoint-0");

        recorder.flush();

        Map<String, Long> deltas = capturedToolDeltas();
        assertEquals(NodeUsageRecorder.MAX_TRACKED_KEYS, deltas.size());
        assertEquals(2L, deltas.get("endpoint-0"), "an already-tracked key keeps counting past the ceiling");
    }

    @Test
    @DisplayName("records nothing at all when the ledger is switched off")
    void disabledRecorderIsInert() {
        NodeUsageRecorder disabled = new NodeUsageRecorder(repository, publisher, false);
        disabled.record("node:agent");
        disabled.record("tool:slack-post-message");
        disabled.flush();

        verify(repository, never()).addCounts(anyMap());
        verify(publisher, never()).publish(anyMap());
    }

    @Test
    @DisplayName("counts every launch when many run at once")
    void countsAreExactUnderConcurrency() throws Exception {
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        recorder.record("node:agent");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "workers finished");
        pool.shutdownNow();

        recorder.flush();

        assertEquals((long) threads * perThread, capturedNodeDeltas().get("agent"));
    }
}
