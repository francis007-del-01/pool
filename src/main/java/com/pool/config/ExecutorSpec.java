package com.pool.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Configuration for a TPS-based executor with hierarchical support.
 */
@Data
public class ExecutorSpec {

    /**
     * Unique executor ID.
     */
    @NotBlank
    private String id;

    /**
     * Parent executor ID (null for root executor).
     */
    private String parent;

    /**
     * Max TPS limit (0 or negative means unbounded).
     */
    private int tps;

    /**
     * Max queue capacity (only applicable for root executor).
     */
    private int queueCapacity;

    /**
     * Field expression to extract unique identifier for TPS counting (e.g., "$req.ipAddress").
     */
    private String identifierField;

    /**
     * Create a root executor with TPS limit and queue capacity.
     */
    public static ExecutorSpec root(String id, int tps, int queueCapacity) {
        return root(id, tps, queueCapacity, null);
    }

    /**
     * Create a root executor with TPS limit, queue capacity, and identifier field.
     */
    public static ExecutorSpec root(String id, int tps, int queueCapacity, String identifierField) {
        ExecutorSpec spec = new ExecutorSpec();
        spec.setId(id);
        spec.setTps(tps);
        spec.setQueueCapacity(queueCapacity);
        spec.setIdentifierField(identifierField);
        return spec;
    }

    /**
     * Create a child executor with TPS limit.
     */
    public static ExecutorSpec child(String id, String parent, int tps) {
        return child(id, parent, tps, null);
    }

    /**
     * Create a child executor with TPS limit and identifier field.
     */
    public static ExecutorSpec child(String id, String parent, int tps, String identifierField) {
        ExecutorSpec spec = new ExecutorSpec();
        spec.setId(id);
        spec.setParent(parent);
        spec.setTps(tps);
        spec.setIdentifierField(identifierField);
        return spec;
    }

    /**
     * Create a child executor with TPS limit, queue capacity, and identifier field.
     */
    public static ExecutorSpec child(String id, String parent, int tps, int queueCapacity, String identifierField) {
        ExecutorSpec spec = new ExecutorSpec();
        spec.setId(id);
        spec.setParent(parent);
        spec.setTps(tps);
        spec.setQueueCapacity(queueCapacity);
        spec.setIdentifierField(identifierField);
        return spec;
    }

    /**
     * Create an unbounded root executor.
     */
    public static ExecutorSpec unboundedRoot(String id, int queueCapacity) {
        return root(id, 0, queueCapacity);
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
