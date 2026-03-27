package com.pool.adapter.executor.tps;

import com.pool.config.ExecutorHierarchy;
import com.pool.core.TpsCounter;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TPS-based admission control gate.
 * Manages TPS limits for hierarchical executors using simple invocation counters.
 *
 * Children consume from parent: a request admitted to a child executor
 * increments counters at every level in the chain (leaf → root).
 * Max caps only — no guaranteed minimums.
 */
public class TpsGate {

    private static final Logger log = LoggerFactory.getLogger(TpsGate.class);

    @Getter
    private final ExecutorHierarchy hierarchy;
    private final ConcurrentHashMap<String, TpsCounter> counters;
    @Getter
    private final long windowSizeMs;
    private final ReentrantLock acquireLock = new ReentrantLock();

    public TpsGate(ExecutorHierarchy hierarchy,
                   ConcurrentHashMap<String, TpsCounter> counters,
                   long windowSizeMs) {
        this.hierarchy = hierarchy;
        this.counters = counters;
        this.windowSizeMs = windowSizeMs;

        log.info("TpsGate initialized: windowSize={}ms, executors={}", windowSizeMs, counters.size());
    }

    /**
     * Convenience constructor for testing.
     */
    public TpsGate(ExecutorHierarchy hierarchy) {
        this(hierarchy, 1000);
    }

    public TpsGate(ExecutorHierarchy hierarchy, long windowSizeMs) {
        this(hierarchy, initCounters(hierarchy, windowSizeMs), windowSizeMs);
    }

    /**
     * Try to acquire permission to execute a request.
     * Checks TPS limits for the executor and all its ancestors.
     *
     * @param executorId Target executor ID
     * @return true if acquired, false if any level's TPS limit is exceeded
     */
    public boolean tryAcquire(String executorId) {
        if (executorId == null || executorId.isEmpty()) {
            throw new IllegalArgumentException("Executor ID cannot be null or empty");
        }

        List<String> chain = hierarchy.getExecutorChain(executorId);

        acquireLock.lock();
        try {
            for (String execId : chain) {
                int maxTps = hierarchy.getTps(execId);
                TpsCounter counter = counters.get(execId);
                if (counter != null && !counter.hasCapacity(maxTps)) {
                    log.debug("TPS limit reached for executor '{}' ({}/{}), rejecting",
                            execId, counter.getCount(), maxTps);
                    return false;
                }
            }

            for (String execId : chain) {
                TpsCounter counter = counters.get(execId);
                if (counter != null) {
                    counter.increment();
                }
            }

            log.debug("Request acquired for executor '{}' (chain: {})", executorId, chain);
            return true;
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * Check if an executor has TPS capacity available.
     */
    public boolean hasCapacity(String executorId) {
        int maxTps = hierarchy.getTps(executorId);
        if (maxTps <= 0) return true;

        TpsCounter counter = counters.get(executorId);
        if (counter == null) return true;

        return counter.hasCapacity(maxTps);
    }

    /**
     * Check if an executor and all its ancestors have capacity.
     */
    public boolean hasCapacityWithAncestors(String executorId) {
        List<String> chain = hierarchy.getExecutorChain(executorId);
        for (String execId : chain) {
            if (!hasCapacity(execId)) return false;
        }
        return true;
    }

    /**
     * Get current TPS for an executor.
     */
    public int getCurrentTps(String executorId) {
        TpsCounter counter = counters.get(executorId);
        return counter != null ? counter.getCount() : 0;
    }

    /**
     * Get available TPS capacity for an executor.
     */
    public int getAvailableCapacity(String executorId) {
        int maxTps = hierarchy.getTps(executorId);
        if (maxTps <= 0) return Integer.MAX_VALUE;

        int current = getCurrentTps(executorId);
        return Math.max(0, maxTps - current);
    }

    /**
     * Get all executor IDs that have capacity.
     */
    public List<String> getExecutorsWithCapacity() {
        return hierarchy.getAllExecutorIds().stream()
                .filter(this::hasCapacity)
                .toList();
    }

    /**
     * Get TPS counter for a specific executor (for wiring eviction callbacks).
     */
    public TpsCounter getCounter(String executorId) {
        return counters.get(executorId);
    }

    /**
     * Clear all counters (for testing).
     */
    public void clear() {
        for (TpsCounter counter : counters.values()) {
            counter.clear();
        }
    }

    private static ConcurrentHashMap<String, TpsCounter> initCounters(
            ExecutorHierarchy hierarchy, long windowSizeMs) {
        ConcurrentHashMap<String, TpsCounter> map = new ConcurrentHashMap<>();
        for (String executorId : hierarchy.getAllExecutorIds()) {
            map.put(executorId, new TpsCounter(windowSizeMs));
        }
        return map;
    }
}
