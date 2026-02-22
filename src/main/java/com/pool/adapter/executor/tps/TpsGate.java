package com.pool.adapter.executor.tps;

import com.pool.core.TaskContext;
import com.pool.variable.DefaultVariableResolver;
import com.pool.variable.VariableResolver;
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

    private final ExecutorHierarchy hierarchy;
    private final ConcurrentHashMap<String, SlidingWindowCounter> counters;
    private final long windowSizeMs;
    private final VariableResolver variableResolver;

    public TpsGate(ExecutorHierarchy hierarchy) {
        this(hierarchy, 1000); // Default 1 second window
    }

    public TpsGate(ExecutorHierarchy hierarchy, long windowSizeMs) {
        this.hierarchy = hierarchy;
        this.counters = new ConcurrentHashMap<>();
        this.windowSizeMs = windowSizeMs;
        this.variableResolver = new DefaultVariableResolver();
        
        // Initialize counters for all executors
        for (String executorId : hierarchy.getAllExecutorIds()) {
            counters.put(executorId, new SlidingWindowCounter(windowSizeMs));
        }
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

        // Get the executor chain (self + ancestors)
        List<String> chain = hierarchy.getExecutorChain(executorId);
        
        // For each executor in chain, check capacity and try to add identifier
        // If identifier already exists in window, it's allowed (already counted)
        // If identifier is new, check capacity first
        for (String execId : chain) {
            String identifier = resolveIdentifier(context, execId);
            SlidingWindowCounter counter = counters.get(execId);
            
            if (counter == null) {
                continue;
            }
            
            // If identifier already in window, allow it (no new slot needed)
            if (counter.contains(identifier)) {
                log.debug("Identifier '{}' already in window for executor '{}', allowing", 
                        identifier, execId);
                continue;
            }
            
            // New identifier - check if we have capacity
            if (!hasCapacity(execId)) {
                log.debug("TPS limit reached for executor '{}', rejecting new identifier '{}'", 
                        execId, identifier);
                return false;
            }
        }
        
        // All have capacity or identifier already exists, add new identifiers
        for (String execId : chain) {
            String identifier = resolveIdentifier(context, execId);
            SlidingWindowCounter counter = counters.get(execId);
            if (counter != null) {
                // tryAdd only adds if not already present
                counter.tryAdd(identifier);
            }
        }
        
        log.debug("Request acquired for executor '{}' (chain: {})", executorId, chain);
        return true;
    }

    /**
     * Try to acquire permission using a simple request ID (for backward compatibility).
     * Uses the same ID for all executors in the chain.
     *
     * @param requestId  Unique request identifier
     * @param executorId Target executor ID
     * @return true if acquired, false if TPS limit exceeded
     */
    public boolean tryAcquire(String requestId, String executorId) {
        if (requestId == null || requestId.isEmpty()) {
            throw new IllegalArgumentException("Request ID cannot be null or empty");
        }
        if (executorId == null || executorId.isEmpty()) {
            throw new IllegalArgumentException("Executor ID cannot be null or empty");
        }

        // Get the executor chain (self + ancestors)
        List<String> chain = hierarchy.getExecutorChain(executorId);
        
        // Check capacity for new identifiers only
        for (String execId : chain) {
            SlidingWindowCounter counter = counters.get(execId);
            if (counter == null) {
                continue;
            }
            
            // If identifier already in window, allow it
            if (counter.contains(requestId)) {
                continue;
            }
            
            // New identifier - check capacity
            if (!hasCapacity(execId)) {
                log.debug("TPS limit reached for executor '{}', rejecting request '{}'", 
                        execId, requestId);
                return false;
            }
        }
        
        // Add new identifiers (tryAdd skips duplicates)
        for (String execId : chain) {
            SlidingWindowCounter counter = counters.get(execId);
            if (counter != null) {
                counter.tryAdd(requestId);
            }
        }
        
        log.debug("Request '{}' acquired for executor '{}' (chain: {})", 
                requestId, executorId, chain);
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
     * Get the underlying hierarchy.
     */
    public ExecutorHierarchy getHierarchy() {
        return hierarchy;
    }

    /**
     * Get window size in milliseconds.
     */
    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    /**
     * Clear all counters (for testing).
     */
    public void clear() {
        for (SlidingWindowCounter counter : counters.values()) {
            counter.clear();
        }
    }
}
