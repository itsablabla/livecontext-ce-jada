package com.apimarketplace.common.web;

import com.apimarketplace.common.streamingapp.StreamingTestApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The streaming executor on the real Spring MVC async path, in a full application context.
 *
 * <p>{@link CommonAsyncConfigMvcStreamingTest} exercises the bean and replays the configurer chain
 * by hand, which is fast but models Spring rather than running it. The two claims this change rests
 * on can only be observed end to end: that Spring MVC hands a {@code StreamingResponseBody} to THIS
 * executor in preference to Spring Boot's own, and that when the pool and its queue are full the
 * request is still SERVED rather than failed. The second is the entire argument for
 * {@code RunInCallerUnlessShuttingDown}, and it lived only in a javadoc paragraph until this class.
 *
 * <p>The application under test lives in {@link StreamingTestApp}, in a package this library does
 * not occupy, so {@code CommonAsyncConfig} loads as an auto-configuration rather than being swept
 * up by a component scan. See that class for why the distinction decides whether these assertions
 * mean anything at all.
 */
@SpringBootTest(classes = StreamingTestApp.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // One worker and no queue (queue-capacity=0 gives a SynchronousQueue): the second
        // concurrent stream has nowhere to go, which is the overflow branch. With AbortPolicy it
        // would fail; caller-runs serves it on the request thread.
        "livecontext.web.async.core-size=1",
        "livecontext.web.async.max-size=1",
        "livecontext.web.async.queue-capacity=0",
        "spring.main.banner-mode=off",
        // This context loads common-lib's auto-configurations, which include the gateway HMAC
        // filter; it refuses to start without a key. Irrelevant here, and turning verification off
        // is cleaner than inventing a secret in a test fixture.
        "gateway.filter.verification-enabled=false",
})
class MvcStreamingAsyncIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @Autowired
    private ThreadPoolTaskExecutor mvcStreamingTaskExecutor;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Spring MVC installs the streaming executor on the handler adapter, beating Boot's own")
    void handlerAdapterUsesTheStreamingExecutor() throws Exception {
        // The assertion below only means something if there was a competitor. Boot registers
        // applicationTaskExecutor and its own WebMvcConfigurer sets it here at order 0. In a
        // context that suppressed it (which is what happens when CommonAsyncConfig is
        // component-scanned instead of auto-configured) nothing else would ever set a task
        // executor, and the assertion would hold for any wiring at all, including a broken one.
        assertThat(context.containsBean("applicationTaskExecutor"))
                .as("no competing executor in this context, so the assertion below proves nothing")
                .isTrue();

        // The real RequestMappingHandlerAdapter built by Spring Boot, not a replayed configurer
        // chain. This is what WebAsyncManager reads for every StreamingResponseBody return value.
        // The field has no getter, hence the reflection.
        Field taskExecutor = RequestMappingHandlerAdapter.class.getDeclaredField("taskExecutor");
        taskExecutor.setAccessible(true);

        assertThat(taskExecutor.get(handlerAdapter))
                .as("Spring Boot's configurer set applicationTaskExecutor and was not overridden")
                .isSameAs(mvcStreamingTaskExecutor);
    }

    @Test
    @DisplayName("a second stream with the pool full is SERVED, not failed")
    void overflowStreamIsStillServedEndToEnd() throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> blocked = client.submit(() -> mvc.perform(get("/blocking-stream")).andReturn());

            assertThat(StreamingTestApp.StreamingController.OCCUPIED.await(10, TimeUnit.SECONDS))
                    .as("the single stream worker never picked up the first request")
                    .isTrue();

            // The pool has one worker, busy, and no queue. AbortPolicy would surface here as an
            // error response; the shipped policy runs the stream on this request's own thread.
            MvcResult overflow = mvc.perform(get("/quick-stream")).andReturn();
            mvc.perform(asyncDispatch(overflow))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .isEqualTo("quick"));

            StreamingTestApp.StreamingController.RELEASE.countDown();
            mvc.perform(asyncDispatch(blocked.get(10, TimeUnit.SECONDS)))
                    .andExpect(status().isOk());
        } finally {
            StreamingTestApp.StreamingController.RELEASE.countDown();
            client.shutdownNow();
        }
    }
}
