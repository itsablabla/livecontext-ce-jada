package com.apimarketplace.orchestrator.services.usage;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts node launches platform-wide, in memory, and flushes them on a timer.
 *
 * <p>WHAT IS COUNTED: one increment per DISPATCH of a node, which is where the engine
 * emits its start. Two consequences worth knowing before reading a number here.
 *
 * <p>A split is ONE dispatch however many items it fans out over - the fan-out happens
 * below this point, inside the split executor. That is the right unit for this ranking:
 * counting items would let a single 500-row split outrank every integration on the
 * platform, which measures one workflow's input size, not what people build with. A loop
 * IS counted per iteration, because each turn is a fresh dispatch.
 *
 * <p>A mocked run counts too. The ranking answers "what do people build and run with",
 * and a builder testing an integration is using it.
 *
 * <p>WHY IN MEMORY. A launch must not pay for a database round trip. Increments go
 * into a {@link LongAdder} per key (contended-write friendly by design) and the
 * whole window leaves as one batched statement plus one HTTP call.
 *
 * <p>WHAT THAT COSTS. The window is lost if the pod is killed between flushes. That
 * is deliberate and is why nothing here may ever back a billing or quota decision:
 * these numbers order a list.
 */
@Component
public class NodeUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(NodeUsageRecorder.class);

    /** Routes a key to its owner - see {@code ExecutionNode.usageKey()}. */
    static final String NODE_PREFIX = "node:";
    static final String TOOL_PREFIX = "tool:";

    /**
     * Ceiling on DISTINCT keys held between flushes. Node types are a closed set and
     * endpoints are a catalog-sized one, so this is never reached in normal operation:
     * it exists so that a malformed plan writing unbounded identifiers cannot grow the
     * map without limit. Once reached, new keys are dropped (existing ones keep
     * counting) until a flush empties the map.
     */
    static final int MAX_TRACKED_KEYS = 10_000;

    /** Longest identifier accepted, matching {@code node_usage_stats.node_key}. */
    static final int MAX_KEY_LENGTH = 160;

    private final ConcurrentHashMap<String, LongAdder> pending = new ConcurrentHashMap<>();

    private final NodeUsageRepository repository;
    private final ToolUsagePublisher toolUsagePublisher;
    private final boolean enabled;

    public NodeUsageRecorder(NodeUsageRepository repository,
                             ToolUsagePublisher toolUsagePublisher,
                             @Value("${orchestrator.node-usage.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.toolUsagePublisher = toolUsagePublisher;
        this.enabled = enabled;
    }

    /**
     * Record one launch. Never throws: a counter must not be able to fail a run.
     *
     * @param usageKey a {@code node:}/{@code tool:} key, or null for a node that opts out
     */
    public void record(String usageKey) {
        if (!enabled || usageKey == null) {
            return;
        }
        String key = usageKey.trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return;
        }
        if (!key.startsWith(NODE_PREFIX) && !key.startsWith(TOOL_PREFIX)) {
            return;
        }
        try {
            LongAdder adder = pending.get(key);
            if (adder == null) {
                if (pending.size() >= MAX_TRACKED_KEYS) {
                    return;
                }
                adder = pending.computeIfAbsent(key, k -> new LongAdder());
            }
            adder.increment();
        } catch (Exception e) {
            log.debug("Node usage not recorded for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Drain the window and persist it.
     *
     * <p>{@code sumThenReset} takes each key's count and zeroes it in one step, so a
     * launch racing the flush is either in this window or the next one, never double
     * counted. Keys stay in the map on purpose - the set of keys is what
     * {@link #MAX_TRACKED_KEYS} bounds, and re-creating a hot adder every minute would
     * only add allocation.
     */
    @Scheduled(
            initialDelayString = "${orchestrator.node-usage.flush-interval-ms:60000}",
            fixedDelayString = "${orchestrator.node-usage.flush-interval-ms:60000}")
    public void flush() {
        if (!enabled || pending.isEmpty()) {
            return;
        }
        Map<String, Long> nodeDeltas = new HashMap<>();
        Map<String, Long> toolDeltas = new HashMap<>();
        pending.forEach((key, adder) -> {
            long delta = adder.sumThenReset();
            if (delta <= 0) {
                return;
            }
            if (key.startsWith(TOOL_PREFIX)) {
                toolDeltas.put(key.substring(TOOL_PREFIX.length()), delta);
            } else {
                nodeDeltas.put(key.substring(NODE_PREFIX.length()), delta);
            }
        });

        if (!nodeDeltas.isEmpty()) {
            try {
                repository.addCounts(nodeDeltas);
            } catch (Exception e) {
                log.warn("Could not flush node usage ({} keys): {}", nodeDeltas.size(), e.getMessage());
                giveBack(NODE_PREFIX, nodeDeltas);
            }
        }
        if (!toolDeltas.isEmpty() && !toolUsagePublisher.publish(toolDeltas)) {
            giveBack(TOOL_PREFIX, toolDeltas);
        }
    }

    /**
     * Return a failed window to the accumulator so the next flush carries it.
     *
     * <p>Safe against unbounded growth: giving back changes the VALUE of keys that are
     * already present, never the number of keys, which is what {@link #MAX_TRACKED_KEYS}
     * bounds.
     */
    private void giveBack(String prefix, Map<String, Long> deltas) {
        deltas.forEach((key, delta) -> {
            LongAdder adder = pending.get(prefix + key);
            if (adder != null) {
                adder.add(delta);
            }
        });
    }

    /** Flush the open window on a graceful shutdown rather than dropping it. */
    @PreDestroy
    public void flushOnShutdown() {
        try {
            flush();
        } catch (Exception e) {
            log.debug("Final node-usage flush skipped: {}", e.getMessage());
        }
    }
}
