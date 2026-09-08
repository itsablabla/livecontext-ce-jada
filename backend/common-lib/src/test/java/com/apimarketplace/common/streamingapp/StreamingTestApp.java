package com.apimarketplace.common.streamingapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Application under test for {@code MvcStreamingAsyncIntegrationTest}.
 *
 * <p><b>It lives in its own package deliberately.</b> An earlier version nested it inside the test
 * class, which sits in {@code com.apimarketplace.common.web}, so its component scan swept up
 * {@code CommonAsyncConfig} as an ordinary user {@code @Configuration} instead of letting it load
 * as an auto-configuration. The context's own condition report showed the consequence:
 *
 * <pre>
 * TaskExecutorConfigurations.TaskExecutorConfiguration:
 *    @ConditionalOnMissingBean (types: java.util.concurrent.Executor)
 *    found beans of type 'java.util.concurrent.Executor' mvcStreamingTaskExecutor
 * </pre>
 *
 * That is the exact shape {@code @AutoConfigureAfter} exists to prevent: no
 * {@code applicationTaskExecutor}, so Spring Boot's own {@code WebMvcConfigurer} never installs a
 * task executor and there is no competing configurer left to beat. A test billed as running Spring
 * rather than modelling it was modelling a shape production cannot reach, and its ordering
 * assertion held for any wiring at all.
 *
 * <p>From here the scan reaches nothing of the library, so {@code CommonAsyncConfig} arrives
 * through {@code AutoConfiguration.imports}, ordered, exactly as it does in the twelve services.
 */
@SpringBootApplication
public class StreamingTestApp {

    @RestController
    public static class StreamingController {

        /** Blocks until released, so it occupies the single worker for the whole test. */
        public static final CountDownLatch RELEASE = new CountDownLatch(1);
        public static final CountDownLatch OCCUPIED = new CountDownLatch(1);

        @GetMapping("/blocking-stream")
        StreamingResponseBody blocking() {
            return out -> {
                OCCUPIED.countDown();
                try {
                    RELEASE.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out.write("blocked".getBytes(StandardCharsets.UTF_8));
            };
        }

        @GetMapping("/quick-stream")
        StreamingResponseBody quick() {
            return out -> out.write("quick".getBytes(StandardCharsets.UTF_8));
        }
    }
}
