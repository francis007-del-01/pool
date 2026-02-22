package com.pool.config;

/**
 * Configuration for a TPS-based executor with hierarchical support.
 *
 * @param id                     Unique executor ID
 * @param parent                 Parent executor ID (null for root executor)
 * @param tps                    Max TPS limit (0 or negative means unbounded)
 * @param queueCapacity          Max queue capacity (only applicable for root executor)
 * @param identifierField        Field expression to extract unique identifier for TPS counting (e.g., "$req.ipAddress")
 */
public record ExecutorSpec(
        String id,
        String parent,
        int tps,
        int queueCapacity,
        String identifierField
) {
    /**
     * Create a root executor with TPS limit and queue capacity.
     */
    public static ExecutorSpec root(String id, int tps, int queueCapacity) {
        return new ExecutorSpec(id, null, tps, queueCapacity, null);
    }

    /**
     * Create a root executor with TPS limit, queue capacity, and identifier field.
     */
    public static ExecutorSpec root(String id, int tps, int queueCapacity, String identifierField) {
        return new ExecutorSpec(id, null, tps, queueCapacity, identifierField);
    }

    /**
     * Create a child executor with TPS limit.
     */
    public static ExecutorSpec child(String id, String parent, int tps) {
        return new ExecutorSpec(id, parent, tps, 0, null);
    }

    /**
     * Create a child executor with TPS limit and identifier field.
     */
    public static ExecutorSpec child(String id, String parent, int tps, String identifierField) {
        return new ExecutorSpec(id, parent, tps, 0, identifierField);
    }

    /**
     * Create an unbounded root executor.
     */
    public static ExecutorSpec unboundedRoot(String id, int queueCapacity) {
        return new ExecutorSpec(id, null, 0, queueCapacity, null);
    }

    /**
     * Check if this is the root executor (no parent).
     */
    public boolean isRoot() {
        return parent == null || parent.isEmpty();
    }

    /**
     * Check if this executor has a TPS limit.
     */
    public boolean hasTpsLimit() {
        return tps > 0;
    }

    /**
     * Default thread name prefix derived from the executor ID.
     */
    public String threadNamePrefix() {
        return id + "-worker-";
    }
}
