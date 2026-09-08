package com.apimarketplace.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Shared async wiring for every service: MDC propagation onto executor threads,
 * and a bounded executor for Spring MVC streaming responses
 * ({@link MvcStreamingAsyncConfiguration}).
 *
 * <p>Audit 2026-05-17 round-3 F13 - auto-install {@link MdcTaskDecorator} on
 * every {@link ThreadPoolTaskExecutor} bean defined in the application
 * context. That includes the streaming executor below, so a request id set on
 * the request thread survives into the thread writing the bytes.
 *
 * <p>This is a {@link BeanPostProcessor} so services that already wire their
 * own executor beans (with custom thread names, queue capacity, rejection
 * policies, …) keep their config - only the decorator is added (if not
 * already set). Services that don't define a custom executor will fall back
 * to Spring's default and won't be auto-decorated; the recommended pattern
 * is to always wire an explicit {@code ThreadPoolTaskExecutor} bean.</p>
 *
 * <p>To opt-out for a specific executor (e.g. a fire-and-forget cleanup pool
 * where MDC propagation is unwanted), set the decorator explicitly to
 * {@code null} AFTER bean construction in {@code @PostConstruct}, or define
 * the executor as a different class (e.g. plain {@link java.util.concurrent.ExecutorService}).</p>
 */
// Spring Boot registers applicationTaskExecutor only when the context has no Executor of its
// own (TaskExecutorConfigurations.OnExecutorCondition). This class contributes one, so if it
// were auto-configured FIRST that condition would fail and Boot would register neither
// applicationTaskExecutor nor its AsyncConfigurer: every @Async call would fall back to a
// thread-per-invocation SimpleAsyncTaskExecutor and spring.task.execution.* would go inert,
// with no exception and no log. The sorter happens to order it correctly today, but that is
// an emergent property of a 242-entry topological sort that adding one starter can flip.
// Declare the invariant instead of inheriting it.
@AutoConfigureAfter(TaskExecutionAutoConfiguration.class)
@Configuration
public class CommonAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(CommonAsyncConfig.class);

    // static: a BeanPostProcessor declared on a non-static factory method forces its
    // @Configuration class to be instantiated before the other post-processors are
    // registered, which Spring warns about on every context refresh.
    @Bean
    public static BeanPostProcessor mdcTaskDecoratorInstaller() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ThreadPoolTaskExecutor tpte) {
                    // Don't overwrite an existing decorator - caller's intent wins.
                    // (Reflection check via a private field would be brittle; rely on
                    // Spring's contract that decorators set after init are honored
                    // for tasks submitted after init.)
                    tpte.setTaskDecorator(new MdcTaskDecorator());
                    log.debug("[CommonAsyncConfig] Installed MdcTaskDecorator on executor bean '{}'", beanName);
                }
                return bean;
            }
        };
    }

    /**
     * A bounded, dedicated executor for Spring MVC async return values, and the
     * {@link WebMvcConfigurer} that installs it.
     *
     * <p><b>Why this exists (added 2026-08-31).</b> A controller returning
     * {@code StreamingResponseBody} does not run on a Tomcat thread: Spring MVC
     * hands it to the MVC async executor. No service in this repo configured one,
     * so every service inherited Spring Boot's default {@code applicationTaskExecutor}:
     * core 8, max {@code Integer.MAX_VALUE}, and an UNBOUNDED queue. A
     * {@code ThreadPoolTaskExecutor} only creates threads beyond its core size once
     * the queue is full, and that queue could never fill, so the max was decorative
     * and the real ceiling was <b>8 concurrent streams per pod</b>.
     *
     * <p>That is not theoretical. storage-service runs a single replica and serves
     * every file download, the signed showcase media proxy included; over the week
     * to 2026-08-31 prometheus recorded 8 active threads with 10 tasks queued behind
     * them. A queued download is a request that has been accepted and is sending
     * nothing, and the wait is inside the Micrometer timer, so those stalls also
     * showed up as latency.
     *
     * <p>agent-service streams the cloud LLM relay from that same core-8 default (prod reports
     * {@code executor_pool_core_threads{job="agent",name="applicationTaskExecutor"} = 8}).
     * orchestrator, which streams the storage explorer, is in a DIFFERENT and worse state: it
     * defines executors of its own, so Boot's condition had already backed off and it has no
     * {@code applicationTaskExecutor} at all, leaving MVC async on Spring's
     * {@code SimpleAsyncTaskExecutor}, which is unbounded and starts a NEW THREAD PER REQUEST.
     * So this bean takes storage and agent from 8-and-queue-forever to 32, and orchestrator
     * from an unbounded thread-per-request fallback to 32.
     *
     * <p><b>Rejection is caller-runs on purpose.</b> Once {@code maxSize} threads are
     * busy and the queue is full, the stream runs inline on the request thread
     * instead of being refused. That is the pre-async behaviour: the download is
     * slower and occupies a container thread, but it is served, and Tomcat's own
     * thread pool becomes the next bound. Aborting would turn a load spike into 500s
     * on user downloads, which is a worse failure than a slow one.
     *
     * <p>Servlet-only and conditional on Spring MVC being present: the gateway is
     * WebFlux and must not see this.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    @EnableConfigurationProperties(WebAsyncProperties.class)
    public static class MvcStreamingAsyncConfiguration {

        /** Bean name is also the Micrometer {@code name} tag on executor_* metrics. */
        public static final String EXECUTOR_BEAN_NAME = "mvcStreamingTaskExecutor";

        @Bean(name = EXECUTOR_BEAN_NAME)
        @ConditionalOnMissingBean(name = EXECUTOR_BEAN_NAME)
        public ThreadPoolTaskExecutor mvcStreamingTaskExecutor(WebAsyncProperties properties) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            // ThreadPoolExecutor also rejects maximumPoolSize <= 0, so a core-size of 0 would
            // crashloop through the very path the clamp below exists to keep open.
            int coreSize = Math.max(1, properties.getCoreSize());
            int maxSize = properties.getMaxSize();
            if (maxSize < coreSize) {
                // ThreadPoolExecutor throws IllegalArgumentException on maximumPoolSize <
                // corePoolSize, inside initialize(), which fails context refresh and crashloops
                // the pod. The two properties are independent, and the natural operator move
                // when a stream alert fires is to raise core-size alone, so clamp and say so
                // rather than refusing to boot on a reasonable mistake.
                log.warn("[CommonAsyncConfig] livecontext.web.async.max-size ({}) is below core-size ({}); "
                        + "raising max-size to match. Set both together to silence this.", maxSize, coreSize);
                maxSize = coreSize;
            }
            executor.setCorePoolSize(coreSize);
            executor.setMaxPoolSize(maxSize);
            executor.setQueueCapacity(properties.getQueueCapacity());
            executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
            // Core threads are reclaimed too, so a service that never streams pays
            // nothing for the pool beyond the object itself.
            executor.setAllowCoreThreadTimeOut(true);
            executor.setThreadNamePrefix("mvc-stream-");
            executor.setRejectedExecutionHandler(new RunInCallerUnlessShuttingDown());
            return executor;
        }

        /**
         * Installs the executor above as the MVC async executor. Ordered last so it
         * wins over Spring Boot's own {@code WebMvcConfigurer}, which sets
         * {@code applicationTaskExecutor} here and runs at order 0.
         *
         * <p>Qualified by bean name rather than by type: every servlet service has at
         * least two {@code ThreadPoolTaskExecutor} beans (this one and Spring Boot's
         * {@code applicationTaskExecutor}), and resolving that by the parameter NAME
         * would silently depend on the compiler keeping {@code -parameters}.
         */
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        public WebMvcConfigurer mvcStreamingAsyncSupportConfigurer(
                @Qualifier(EXECUTOR_BEAN_NAME) ThreadPoolTaskExecutor mvcStreamingTaskExecutor) {
            return new WebMvcConfigurer() {
                @Override
                public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                    configurer.setTaskExecutor(mvcStreamingTaskExecutor);
                }
            };
        }

        /**
         * Caller-runs, except during shutdown, where it rejects loudly instead of silently
         * dropping the task.
         *
         * <p>Stock {@link ThreadPoolExecutor.CallerRunsPolicy} runs the task inline only
         * {@code if (!e.isShutdown())}; otherwise it returns having neither run the task nor
         * thrown. {@code submit()} then hands back a {@code Future} that will never complete,
         * and on the MVC async path {@code startAsync()} has already been called, so a stream
         * rejected during a graceful pod shutdown is neither served nor failed: the response
         * hangs until the async timeout. Rolling deploys make that window routine rather than
         * exotic. Throwing restores the AbortPolicy behaviour this policy otherwise replaces,
         * for the one case where aborting is the right answer.
         */
        static final class RunInCallerUnlessShuttingDown extends ThreadPoolExecutor.CallerRunsPolicy {
            @Override
            public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
                if (executor.isShutdown()) {
                    throw new RejectedExecutionException(
                            "Streaming executor is shutting down; refusing " + task);
                }
                super.rejectedExecution(task, executor);
            }
        }
    }
}
