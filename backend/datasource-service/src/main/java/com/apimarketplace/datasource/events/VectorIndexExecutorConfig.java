package com.apimarketplace.datasource.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The pool that builds and drops per-datasource HNSW indexes.
 *
 * <p><b>Deliberately tiny, and sized by the right resource.</b> Concurrent
 * {@code CREATE INDEX CONCURRENTLY} calls on the same table serialize on its
 * SHARE UPDATE EXCLUSIVE lock, so eight threads never meant eight simultaneous
 * builds; they meant eight PgBouncer server slots pinned for the whole queue,
 * out of the datasource alias's forty, each waiting for every older snapshot
 * including the 03:00 {@code pg_dump}. Two threads make builds queue on a pool
 * nobody else shares instead of on the service's connection pool.
 *
 * <p><b>Not exposed as a {@code java.util.concurrent.Executor} bean, and that
 * is load-bearing.</b> Spring Boot registers {@code applicationTaskExecutor}
 * only when the context holds no {@code Executor} of its own
 * ({@code TaskExecutorConfigurations.OnExecutorCondition}), and a
 * component-scanned {@code @Bean} of that type registers before the deferred
 * auto-configuration and fails the condition. The service would then lose
 * {@code applicationTaskExecutor} and its {@code AsyncConfigurer} silently, and
 * every unqualified {@code @Async} in it, which includes the row-event listener
 * that calls trigger-service on every row write, would fall back to a
 * thread-per-invocation {@code SimpleAsyncTaskExecutor}: a bulk import becomes
 * a thread storm, with no error and no log. {@code CommonAsyncConfig} documents
 * the same trap. Wrapping the pool in a type that is not an {@code Executor}
 * keeps it out of that condition entirely; the listener submits to it directly
 * rather than through {@code @Async}.
 *
 * <p><b>Queue full: the caller runs the task.</b> With both threads busy and
 * 64 builds queued, the 67th submit executes inline on the submitting thread,
 * which is the request thread of whoever created the column, inside its
 * after-commit callback. That request then blocks for the build, past the
 * gateway timeout if the build is long. Chosen over dropping the task, because
 * a dropped build is the seq-scan-forever defect this whole class exists to
 * prevent; the queue depth makes it a 67-simultaneous-creations event.
 */
@Configuration
public class VectorIndexExecutorConfig {

    /**
     * The pool, behind a type that is deliberately NOT an Executor. See class doc.
     * Holds its delegate as a plain Executor so a test can hand it
     * {@code Runnable::run} and observe the handlers synchronously.
     */
    public static final class VectorIndexExecutor {
        /** Stateless; one instance serves every submit. */
        private static final com.apimarketplace.common.web.MdcTaskDecorator MDC =
                new com.apimarketplace.common.web.MdcTaskDecorator();

        private final java.util.concurrent.Executor delegate;
        private final int maxPoolSize;
        private final int queueCapacity;
        private final Runnable onShutdown;

        public VectorIndexExecutor(java.util.concurrent.Executor delegate, int maxPoolSize, int queueCapacity,
                                   Runnable onShutdown) {
            this.delegate = delegate;
            this.maxPoolSize = maxPoolSize;
            this.queueCapacity = queueCapacity;
            this.onShutdown = onShutdown;
        }

        /** A synchronous instance for tests: every task runs on the calling thread. */
        public static VectorIndexExecutor direct() {
            return new VectorIndexExecutor(Runnable::run, 1, 0, () -> { });
        }

        /** Package-private: lets a test drive the pool's rejection handler directly. */
        static VectorIndexExecutor over(ThreadPoolTaskExecutor pool) {
            return new VectorIndexExecutor(pool, pool.getMaxPoolSize(), pool.getQueueCapacity(), pool::shutdown);
        }

        /**
         * @param what a one-line description of the task ("HNSW build for datasource
         *             42"), so a task that is REFUSED can be named in the log. The
         *             handler only ever sees an opaque Runnable otherwise.
         */
        public void submit(String what, Runnable task) {
            // The MDC decoration is applied HERE, inside the name, and not through
            // pool.setTaskDecorator. A pool-level decorator wraps the task before the
            // rejection handler ever sees it, so the handler would receive the
            // decorator's lambda and the WARN would print a lambda class name instead
            // of "HNSW build for datasource 42" (caught in review by actually reading
            // the log; the first version claimed the opposite in a comment). With the
            // decoration on the inside, the outermost Runnable is the named one.
            delegate.execute(new NamedTask(what, MDC.decorate(task)));
        }


        /** A Runnable that can say what it is. */
        static final class NamedTask implements Runnable {
            final String what;
            private final Runnable body;

            NamedTask(String what, Runnable body) {
                this.what = what;
                this.body = body;
            }

            @Override
            public void run() {
                body.run();
            }

            @Override
            public String toString() {
                return what;
            }
        }

        public int maxPoolSize() {
            return maxPoolSize;
        }

        public int queueCapacity() {
            return queueCapacity;
        }

        public void shutdown() {
            onShutdown.run();
        }
    }

    /**
     * Because the pool is not a bean, it gets none of the lifecycle Spring gives
     * one, and each piece is re-attached here on purpose rather than left to be
     * inferred:
     * <ul>
     *   <li>{@code destroyMethod} is named explicitly. Spring would infer a public
     *       {@code shutdown()} anyway, but an inferred hook disappears silently on a
     *       rename; a declared one fails loudly.</li>
     *   <li>The MDC decorator is applied by hand, inside {@code submit}, never via
     *       {@code setTaskDecorator}: {@code CommonAsyncConfig}'s post-processor
     *       only sees beans, and a pool-level decorator would wrap the task before
     *       the rejection handler sees it, hiding the task's name from the WARN.</li>
     *   <li>Executor metrics are bound by hand for the same reason. Queue depth is
     *       the one number that predicts the caller-runs case, and without this it
     *       would be unobservable.</li>
     *   <li>Rejection during shutdown is logged, by name. Stock
     *       {@code CallerRunsPolicy} returns WITHOUT running once the pool is shut
     *       down, and once {@code shutdown()} has run EVERY submit is rejected
     *       whatever the queue depth, so any column created or table deleted after
     *       bean destruction began loses its task with no error, and nothing in the
     *       platform retries it. A lost BUILD is invisible afterwards: the index is
     *       simply MISSING, which neither the invalid nor the orphan gauge counts.
     *       Only a lost DROP shows up (as an orphan). The WARN therefore names the
     *       task and says to rebuild that datasource; it does not point at gauges
     *       that cannot show it. Throwing would 500 a request whose transaction
     *       already committed, so it stays a WARN.</li>
     * </ul>
     */
    @Bean(destroyMethod = "shutdown")
    public VectorIndexExecutor vectorIndexExecutor(io.micrometer.core.instrument.MeterRegistry registry) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(64);
        pool.setThreadNamePrefix("vector-index-");
        pool.setRejectedExecutionHandler((task, executor) -> {
            if (executor.isShutdown()) {
                // task is the NamedTask itself: decoration happens inside submit(), not
                // at pool level, precisely so that this toString is the description.
                LOG.warn("[VectorIndexExecutor] pool is shutting down; task NOT run and nothing retries it: {}. "
                        + "If this was an HNSW build, the datasource now has NO index (not invalid, not "
                        + "orphan: the gauges cannot show it) and will sequential-scan until the column "
                        + "is re-created or the index is rebuilt by hand.", task);
                return;
            }
            // Caller runs: the submitting request thread builds the index itself.
            task.run();
        });
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        // The pool is not itself a bean, so Spring will not call afterPropertiesSet
        // on it; initialize it here, once.
        pool.initialize();
        // bindTo, not monitor(): monitor() also builds a TimedExecutorService wrapper
        // that registers two timers on construction; discarding the wrapper would
        // leave those timers at zero forever. The pool gauges are what we want.
        new io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics(
                pool.getThreadPoolExecutor(), "vectorIndexExecutor", io.micrometer.core.instrument.Tags.empty())
                .bindTo(registry);
        return new VectorIndexExecutor(pool, pool.getMaxPoolSize(), pool.getQueueCapacity(), pool::shutdown);
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(VectorIndexExecutorConfig.class);
}
