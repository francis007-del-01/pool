package com.pool.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * Manages hierarchical executor relationships (parent-child).
 * Validates configuration and provides traversal methods.
 */
public class ExecutorHierarchy {

    private static final Logger log = LoggerFactory.getLogger(ExecutorHierarchy.class);

    private final Map<String, ExecutorSpec> executors;
    private final Map<String, List<String>> children; // parent -> children
    private final String rootId;

    public ExecutorHierarchy(PoolConfig config) {
        this(config.getExecutors());
    }

    public ExecutorHierarchy(List<ExecutorSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("Executor specs cannot be null or empty");
        }

        this.executors = new HashMap<>();
        this.children = new HashMap<>();

        // Build executor map
        for (ExecutorSpec spec : specs) {
            if (spec.getId() == null || spec.getId().isEmpty()) {
                throw new IllegalArgumentException("Executor ID cannot be null or empty");
            }
            if (executors.containsKey(spec.getId())) {
                throw new IllegalArgumentException("Duplicate executor ID: " + spec.getId());
            }
            executors.put(spec.getId(), spec);
        }

        // Validate and build hierarchy
        String foundRoot = null;
        for (ExecutorSpec spec : specs) {
            String parentId = spec.getParent();
            
            if (parentId == null || parentId.isEmpty()) {
                // This is a root executor
                if (foundRoot != null) {
                    throw new IllegalArgumentException(
                            "Multiple root executors found: '" + foundRoot + "' and '" + spec.getId() + "'");
                }
                foundRoot = spec.getId();
            } else {
                // Validate parent exists
                if (!executors.containsKey(parentId)) {
                    throw new IllegalArgumentException(
                            "Executor '" + spec.getId() + "' references unknown parent '" + parentId + "'");
                }
                
                // Add to children map
                children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(spec.getId());
            }
        }

        if (foundRoot == null) {
            throw new IllegalArgumentException("No root executor found (executor without parent)");
        }
        this.rootId = foundRoot;

        // Validate no cycles
        validateNoCycles();

        // Validate child TPS <= parent TPS
        validateTpsConstraints();

        log.info("ExecutorHierarchy initialized with root '{}' and {} total executors", 
                rootId, executors.size());
    }

    /**
     * Get executor chain from leaf to root (inclusive).
     * Order: [executorId, parentId, grandparentId, ..., rootId]
     */
    public List<String> getExecutorChain(String executorId) {
        List<String> chain = new ArrayList<>();
        String current = executorId;
        
        while (current != null) {
            if (chain.contains(current)) {
                throw new IllegalStateException("Cycle detected in executor chain: " + chain);
            }
            chain.add(current);
            
            ExecutorSpec spec = executors.get(current);
            if (spec == null) {
                throw new IllegalArgumentException("Unknown executor: " + current);
            }
            current = spec.getParent();
        }
        
        return chain;
    }

    /**
     * Get all executor IDs.
     */
    public Set<String> getAllExecutorIds() {
        return Collections.unmodifiableSet(executors.keySet());
    }

    /**
     * Get TPS limit for an executor.
     */
    public int getTps(String executorId) {
        ExecutorSpec spec = executors.get(executorId);
        return spec != null ? spec.getTps() : 0;
    }

    /**
     * Get identifier field for an executor.
     * Returns null if not configured (use default request ID).
     */
    public String getIdentifierField(String executorId) {
        ExecutorSpec spec = executors.get(executorId);
        return spec != null ? spec.getIdentifierField() : null;
    }

    /**
     * Get executor spec by ID.
     */
    public ExecutorSpec getExecutor(String executorId) {
        return executors.get(executorId);
    }

    /**
     * Get root executor ID.
     */
    public String getRootId() {
        return rootId;
    }

    /**
     * Get root executor spec.
     */
    public ExecutorSpec getRoot() {
        return executors.get(rootId);
    }

    /**
     * Get children of an executor.
     */
    public List<String> getChildren(String executorId) {
        return children.getOrDefault(executorId, Collections.emptyList());
    }

    /**
     * Check if an executor is the root.
     */
    public boolean isRoot(String executorId) {
        return rootId.equals(executorId);
    }

    /**
     * Get parent of an executor.
     */
    public String getParent(String executorId) {
        ExecutorSpec spec = executors.get(executorId);
        return spec != null ? spec.getParent() : null;
    }

    /**
     * Get queue capacity for a specific executor.
     * Returns 0 if not configured (unbounded).
     */
    public int getQueueCapacity(String executorId) {
        ExecutorSpec spec = executors.get(executorId);
        return spec != null ? spec.getQueueCapacity() : 0;
    }

    /**
     * Get all leaf executors (executors with no children).
     */
    public List<String> getLeafExecutors() {
        List<String> leaves = new ArrayList<>();
        for (String id : executors.keySet()) {
            if (!children.containsKey(id) || children.get(id).isEmpty()) {
                leaves.add(id);
            }
        }
        return leaves;
    }

    /**
     * Get depth of an executor in the hierarchy (root = 0).
     */
    public int getDepth(String executorId) {
        int depth = 0;
        String current = executorId;
        
        while (current != null && !isRoot(current)) {
            depth++;
            current = getParent(current);
        }
        
        return depth;
    }

    private void validateNoCycles() {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        
        for (String id : executors.keySet()) {
            if (!visited.contains(id)) {
                if (hasCycle(id, visited, inStack)) {
                    throw new IllegalArgumentException("Circular dependency detected in executor hierarchy");
                }
            }
        }
    }

    private boolean hasCycle(String id, Set<String> visited, Set<String> inStack) {
        visited.add(id);
        inStack.add(id);
        
        List<String> childList = children.get(id);
        if (childList != null) {
            for (String child : childList) {
                if (!visited.contains(child)) {
                    if (hasCycle(child, visited, inStack)) {
                        return true;
                    }
                } else if (inStack.contains(child)) {
                    return true;
                }
            }
        }
        
        inStack.remove(id);
        return false;
    }

    private void validateTpsConstraints() {
        for (ExecutorSpec spec : executors.values()) {
            if (spec.getParent() != null && !spec.getParent().isEmpty()) {
                ExecutorSpec parent = executors.get(spec.getParent());
                
                // If parent has TPS limit and child has TPS limit
                if (parent != null && parent.getTps() > 0 && spec.getTps() > 0) {
                    if (spec.getTps() > parent.getTps()) {
                        throw new IllegalArgumentException(
                                "Child executor '" + spec.getId() + "' TPS (" + spec.getTps() + 
                                ") cannot exceed parent '" + parent.getId() + "' TPS (" + parent.getTps() + ")");
                    }
                }
            }
        }
    }
}
