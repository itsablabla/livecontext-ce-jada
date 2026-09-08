package com.apimarketplace.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sizing for the Spring MVC async executor that runs {@code StreamingResponseBody}
 * return values (file downloads, the signed media proxy, the cloud LLM relay).
 *
 * <p>Defaults are deliberately a FIXED pool: {@code core-size == max-size}. A
 * {@code ThreadPoolTaskExecutor} only grows past its core size once the queue is
 * FULL, so a pool with a large max and a large queue never uses that max, and the
 * max reads as a capacity it does not have. Making the two equal means the number
 * printed here is the number of concurrent streams the service can actually serve.
 *
 * <p>See {@code CommonAsyncConfig.MvcStreamingAsyncConfiguration} for why the
 * executor exists at all.
 */
@ConfigurationProperties(prefix = "livecontext.web.async")
public class WebAsyncProperties {

    /**
     * Concurrent streaming responses this service can serve. Threads here are
     * blocked on socket I/O, not on CPU, so this is bounded by memory (~1 MB of
     * stack each) rather than by cores.
     */
    private int coreSize = 32;

    /**
     * Hard ceiling on stream threads. Equal to {@link #coreSize} by default; see the class
     * javadoc for why a larger value would be misleading rather than useful.
     *
     * <p>Raise this WITH {@link #coreSize}, never below it: {@code ThreadPoolExecutor} rejects
     * {@code maximumPoolSize < corePoolSize}. The configuration clamps and warns rather than
     * failing to boot, but the pool you get is then the one you asked for by accident.
     */
    private int maxSize = 32;

    /**
     * Streams accepted but not yet started. A task waiting here is a download the client has
     * been promised and is receiving no bytes for, and the wait is inside the request timer, so
     * it is counted as latency.
     *
     * <p>Small on purpose, and small relative to {@link #coreSize}. Spring Boot's default is
     * {@code Integer.MAX_VALUE}, which is the defect this class exists to fix, but a large
     * FINITE queue reproduces most of it: with a 100-deep queue on a single-replica service,
     * 100 downloads can still be accepted and parked before the caller-runs fallback engages.
     * 16 absorbs a micro-burst and then hands overflow straight to the request thread, which is
     * the degradation this design actually argues for.
     */
    private int queueCapacity = 16;

    /** Idle stream threads are reclaimed after this many seconds, core threads included. */
    private int keepAliveSeconds = 60;

    public int getCoreSize() {
        return coreSize;
    }

    public void setCoreSize(int coreSize) {
        this.coreSize = coreSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(int keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }
}
