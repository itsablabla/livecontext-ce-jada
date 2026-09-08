package com.apimarketplace.datasource.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The rejection handler is the newest code on the deleting/building path and
 * has two branches that nothing else exercises: saturation (the caller runs the
 * task) and shutdown (the task is dropped, with a WARN naming it).
 */
@DisplayName("VectorIndexExecutorConfig rejection handler")
class VectorIndexExecutorConfigTest {

    private static final int THREADS = 2;
    private static final int QUEUE = 64;

    /**
     * Both threads blocked, queue full: the next submit must run on the caller,
     * here the test thread. That is the documented case where a request thread
     * builds the index itself rather than losing the build.
     */
    @Test
    @DisplayName("when both threads are busy and the queue is full, the caller runs the task itself")
    void saturationRunsOnTheCaller() throws Exception {
        var executor = new VectorIndexExecutorConfig().vectorIndexExecutor(new SimpleMeterRegistry());
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Occupy both threads.
            for (int i = 0; i < THREADS; i++) {
                executor.submit("blocker " + i, () -> await(release));
            }
            // Fill the queue.
            for (int i = 0; i < QUEUE; i++) {
                executor.submit("queued " + i, () -> await(release));
            }
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            executor.submit("overflow", () -> ranOn.set(Thread.currentThread()));

            assertThat(ranOn.get())
                    .as("the overflow task must have run synchronously on the submitting thread")
                    .isSameAs(Thread.currentThread());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    /**
     * After shutdown every submit is rejected whatever the queue depth. The task
     * must NOT run (running work on a caller mid-shutdown is what stock
     * CallerRunsPolicy avoids too) and the submit must not throw (the caller's
     * transaction already committed; a 500 here would be a lie).
     *
     * The WARN is read back from the actual appender, not inferred from a unit
     * on NamedTask: the first version of this class tested NamedTask.toString in
     * isolation and passed while the production path printed a decorator lambda,
     * because a pool-level TaskDecorator wrapped the task before the handler saw
     * it. Only the log line proves what an operator will read.
     */
    @Test
    @DisplayName("after shutdown, a submit neither runs the task nor throws, and the WARN names the task")
    void shutdownDropsWithoutRunningOrThrowingAndNamesTheTask() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(VectorIndexExecutorConfig.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var executor = new VectorIndexExecutorConfig().vectorIndexExecutor(new SimpleMeterRegistry());
            executor.shutdown();
            AtomicBoolean ran = new AtomicBoolean(false);

            assertThatCode(() -> executor.submit("HNSW build for datasource 42", () -> ran.set(true)))
                    .doesNotThrowAnyException();

            assertThat(ran.get())
                    .as("a task submitted after shutdown must be dropped, not run on the caller")
                    .isFalse();
            assertThat(appender.list)
                    .as("the operator must be told WHICH task was lost, not shown a lambda class name")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("HNSW build for datasource 42");
                        assertThat(event.getFormattedMessage()).doesNotContain("$$Lambda");
                        assertThat(event.getFormattedMessage()).contains("NO index");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
