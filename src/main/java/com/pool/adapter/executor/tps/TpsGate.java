package com.pool.adapter.executor.tps;

import com.pool.core.TaskContext;
import com.pool.variable.DefaultVariableResolver;
import com.pool.variable.VariableResolver;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TPS-based admission control gate.
 * Manages TPS limits for hierarchical executors.
 */
public class TpsGate {

    private static final Logger log = LoggerFactory.getLogger(TpsGate.class);

    // Default TTL for admitted requests: 5 minutes
    private static final long DEFAULT_ADMITTED_TTL_MS = 5 * 60 * 1000L;

    /**
     * -- GETTER --
     *  Get the underlying hierarchy.
     */
    @Getter
    private final ExecutorHierarchy hierarchy;
    private final ConcurrentHashMap<String, SlidingWindowCounter> counters;
    /**
     * -- GETTER --
     *  Get window size in milliseconds.
     */
    @Getter
    private final long windowSizeMs;
    private final VariableResolver variableResolver;

    // Per-executor TTL-based admitted sets: requests that have been admitted
    // bypass TPS checks for that specific executor until they expire. This prevents
    // re-counting long-running requests that need additional threads after their
    // TPS window has expired.
    private final ConcurrentHashMap<String, SlidingWindowCounter> admittedIds;
    /**
     * -- GETTER --
     *  Get the admitted TTL in milliseconds.
     */
    @Getter
    private final long admittedTtlMs;


    /**
     * Primary constructor — accepts pre-built counters and dependencies.
     * Used by TpsSystemConfig for centralized initialization.
     */
    public TpsGate(ExecutorHierarchy hierarchy,
                   ConcurrentHashMap<String, SlidingWindowCounter> counters,
                   ConcurrentHashMap<String, SlidingWindowCounter> admittedIds,
                   VariableResolver variableResolver,
                   long windowSizeMs,
                   long admittedTtlMs) {
        this.hierarchy = hierarchy;
        this.counters = counters;
        this.admittedIds = admittedIds;
        this.variableResolver = variableResolver;
        this.windowSizeMs = windowSizeMs;
        this.admittedTtlMs = admittedTtlMs;

        log.info("TpsGate initialized: windowSize={}ms, admittedTtl={}ms", windowSizeMs, admittedTtlMs);
    }

    /**
     * Convenience constructor for testing — creates counters and admitted sets internally.
     */
    public TpsGate(ExecutorHierarchy hierarchy) {
        this(hierarchy, 1000);
    }

    public TpsGate(ExecutorHierarchy hierarchy, long windowSizeMs) {
        this(hierarchy, windowSizeMs, DEFAULT_ADMITTED_TTL_MS);
    }

    public TpsGate(ExecutorHierarchy hierarchy, long windowSizeMs, long admittedTtlMs) {
        this(hierarchy,
             initCounters(hierarchy, windowSizeMs),
             initAdmittedSets(hierarchy, admittedTtlMs),
             new DefaultVariableResolver(),
             windowSizeMs,
             admittedTtlMs);
    }

    /**
     * Try to acquire permission to execute a request.
     * Checks TPS limits for the executor and all its ancestors.
     * Each executor uses its own identifier_field to extract the unique ID from context.
     *
     * @param context    Task context containing request variables
     * @param executorId Target executor ID
     * @return true if acquired, false if TPS limit exceeded
     */
    public boolean tryAcquire(TaskContext context, String executorId) {
        if (context == null) {
            throw new IllegalArgumentException("TaskContext cannot be null");
        }
        if (executorId == null || executorId.isEmpty()) {
            throw new IllegalArgumentException("Executor ID cannot be null or empty");
        }

        // Check if this task was already admitted for this executor — bypass TPS
        // Uses taskId (not resolved identifier) because two different tasks can share
        // the same identifier (e.g., same IP) but each task needs its own admission
        String taskId = context.getTaskId();
        SlidingWindowCounter admittedSet = admittedIds.get(executorId);
        if (admittedSet != null && admittedSet.contains(taskId)) {
            log.debug("Task '{}' already admitted for executor '{}', bypassing TPS",
                    taskId, executorId);
            return true;
        }

        // Get the executor chain (self + ancestors)
        List<String> chain = hierarchy.getExecutorChain(executorId);
        
        // For each executor in chain, check capacity and try to add identifier
        // If identifier already exists in window, it's allowed (already counted)
        // If identifier is new, check capacity first
        for (String execId : chain) {
            String execIdentifier = resolveIdentifier(context, execId);
            SlidingWindowCounter counter = counters.get(execId);
            
            if (counter == null) {
                continue;
            }
            
            // If identifier already in window, allow it (no new slot needed)
            if (counter.contains(execIdentifier)) {
                log.debug("Identifier '{}' already in window for executor '{}', allowing", 
                        execIdentifier, execId);
                continue;
            }
            
            // New identifier - check if we have capacity
            if (!hasCapacity(execId)) {
                log.debug("TPS limit reached for executor '{}', rejecting new identifier '{}'", 
                        execId, execIdentifier);
                return false;
            }
        }
        
        // All have capacity or identifier already exists, add new identifiers
        for (String execId : chain) {
            String execIdentifier = resolveIdentifier(context, execId);
            SlidingWindowCounter counter = counters.get(execId);
            if (counter != null) {
                // tryAdd only adds if not already present
                counter.tryAdd(execIdentifier);
            }
        }
        
        // Mark task as admitted for this executor
        if (admittedSet != null) {
            admittedSet.add(taskId);
        }
        
        log.debug("Request acquired and admitted for executor '{}' (chain: {})", executorId, chain);
        return true;
    }

    /**
     * Resolve identifier for a specific executor from TaskContext.
     * Uses executor's identifier_field if configured, otherwise falls back to task ID.
     */
    private String resolveIdentifier(TaskContext context, String executorId) {
        String identifierField = hierarchy.getIdentifierField(executorId);
        
        // If no identifier field configured, use task ID
        if (identifierField == null || identifierField.isEmpty()) {
            return context.getTaskId();
        }
        
        // Resolve field expression using VariableResolver
        String value = variableResolver.resolveAsString(identifierField, context);
        
        // Fallback to task ID if resolution fails
        return value != null ? value : context.getTaskId();
    }

    /**
     * Check if an executor has TPS capacity available.
     */
    public boolean hasCapacity(String executorId) {
        int maxTps = hierarchy.getTps(executorId);
        
        // maxTps <= 0 means unbounded
        if (maxTps <= 0) {
            return true;
        }
        
        SlidingWindowCounter counter = counters.get(executorId);
        if (counter == null) {
            return true;
        }
        
        return counter.count() < maxTps;
    }

    /**
     * Get current TPS for an executor.
     */
    public int getCurrentTps(String executorId) {
        SlidingWindowCounter counter = counters.get(executorId);
        return counter != null ? counter.count() : 0;
    }

    /**
     * Get available TPS capacity for an executor.
     */
    public int getAvailableCapacity(String executorId) {
        int maxTps = hierarchy.getTps(executorId);
        
        // Unbounded
        if (maxTps <= 0) {
            return Integer.MAX_VALUE;
        }
        
        int current = getCurrentTps(executorId);
        return Math.max(0, maxTps - current);
    }

    /**
     * Release a request from all counters (optional, for cleanup).
     * Normally not needed as sliding window auto-expires.
     */
    public void release(String requestId, String executorId) {
        List<String> chain = hierarchy.getExecutorChain(executorId);
        
        for (String execId : chain) {
            SlidingWindowCounter counter = counters.get(execId);
            if (counter != null) {
                counter.remove(requestId);
            }
        }
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
     * Check if an executor and all its ancestors have capacity.
     */
    public boolean hasCapacityWithAncestors(String executorId) {
        List<String> chain = hierarchy.getExecutorChain(executorId);
        
        for (String execId : chain) {
            if (!hasCapacity(execId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Clear all counters (for testing).
     */
    public void clear() {
        for (SlidingWindowCounter counter : counters.values()) {
            counter.clear();
        }
        for (SlidingWindowCounter admitted : admittedIds.values()) {
            admitted.clear();
        }
    }

    /**
     * Get TPS counter for a specific executor (for config wiring).
     */
    public SlidingWindowCounter getCounter(String executorId) {
        return counters.get(executorId);
    }

    private static ConcurrentHashMap<String, SlidingWindowCounter> initCounters(
            ExecutorHierarchy hierarchy, long windowSizeMs) {
        ConcurrentHashMap<String, SlidingWindowCounter> map = new ConcurrentHashMap<>();
        for (String executorId : hierarchy.getAllExecutorIds()) {
            map.put(executorId, new SlidingWindowCounter(windowSizeMs));
        }
        return map;
    }

    private static ConcurrentHashMap<String, SlidingWindowCounter> initAdmittedSets(
            ExecutorHierarchy hierarchy, long admittedTtlMs) {
        ConcurrentHashMap<String, SlidingWindowCounter> map = new ConcurrentHashMap<>();
        for (String executorId : hierarchy.getAllExecutorIds()) {
            map.put(executorId, new SlidingWindowCounter(admittedTtlMs));
        }
        return map;
    }
}
