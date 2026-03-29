package com.pool.config;

import com.pool.exception.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * Manages hierarchical executor relationships (parent-child).
 * Validates configuration and provides traversal methods.
 *
 * <p>Only root executors (those without a parent) have a queue.
 * All child executors share their root's queue.
 * Multiple root executors are supported — each gets its own independent queue.
 */
public class ExecutorHierarchy {

    private static final Logger log = LoggerFactory.getLogger(ExecutorHierarchy.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;

    private final Map<String, ExecutorSpec> executors;
    private final Map<String, List<String>> children; // parent -> children
    private final Set<String> rootIds;

    public ExecutorHierarchy(PoolConfig config) {
        this(config.getExecutors());
    }

    public ExecutorHierarchy(List<ExecutorSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("Executor specs cannot be null or empty");
        }

        this.executors = new HashMap<>();
        this.children = new HashMap<>();
        this.rootIds = new LinkedHashSet<>();

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
        for (ExecutorSpec spec : specs) {
            String parentId = spec.getParent();

            if (parentId == null || parentId.isEmpty()) {
                rootIds.add(spec.getId());
            } else {
                if (!executors.containsKey(parentId)) {
                    throw new IllegalArgumentException(
                            "Executor '" + spec.getId() + "' references unknown parent '" + parentId + "'");
                }
                if (spec.getQueueCapacity() > 0) {
                    throw new ConfigurationException(
                            "queue-capacity is only valid on root executors: '" + spec.getId() + "'");
                }
                children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(spec.getId());
            }
        }

        if (rootIds.isEmpty()) {
            throw new IllegalArgumentException("No root executor found (executor without parent)");
        }

        validateNoCycles();
        validateTpsConstraints();

        log.info("ExecutorHierarchy initialized: {} root(s) {}, {} total executors",
                rootIds.size(), rootIds, executors.size());
    }

    /**
     * Get executor chain from leaf to root (inclusive).
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
     * Get all root executor IDs.
     */
    public Set<String> getRootIds() {
        return Collections.unmodifiableSet(rootIds);
    }

    /**
     * Get the root executor ID for any executor (walks up the parent chain).
     */
    public String getRootIdFor(String executorId) {
        String current = executorId;
        while (current != null) {
            if (rootIds.contains(current)) {
                return current;
            }
            ExecutorSpec spec = executors.get(current);
            if (spec == null) {
                throw new IllegalArgumentException("Unknown executor: " + current);
            }
            current = spec.getParent();
        }
        throw new IllegalStateException("No root found for executor: " + executorId);
    }

    /**
     * Get TPS limit for an executor.
     */
    public int getTps(String executorId) {
        ExecutorSpec spec = executors.get(executorId);
        return spec != null ? spec.getTps() : 0;
    }

    /**
     * Get executor spec by ID.
     */
    public ExecutorSpec getExecutor(String executorId) {
        return executors.get(executorId);
    }

    /**
     * Get queue capacity for an executor's root queue.
     * Always walks up to the root and reads its capacity.
     * Defaults to {@value DEFAULT_QUEUE_CAPACITY} if root has no explicit capacity set.
     */
    public int getQueueCapacity(String executorId) {
        String rootId = getRootIdFor(executorId);
        ExecutorSpec root = executors.get(rootId);
        int cap = root != null ? root.getQueueCapacity() : 0;
        return cap > 0 ? cap : DEFAULT_QUEUE_CAPACITY;
    }

    /**
     * Get children of an executor.
     */
    public List<String> getChildren(String executorId) {
        return children.getOrDefault(executorId, Collections.emptyList());
    }

    /**
     * Check if an executor is a root (has no parent).
     */
    public boolean isRoot(String executorId) {
        return rootIds.contains(executorId);
    }

    /**
     * Get parent of an executor.
     */
    public String getParent(String executorId) {
        ExecutorSpec spec = executors.get(executorId);
        return spec != null ? spec.getParent() : null;
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
