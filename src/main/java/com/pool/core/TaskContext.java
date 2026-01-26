package com.pool.core;

import java.util.Map;
import java.util.Optional;

/**
 * Context containing all variables needed for priority calculation.
 * Immutable after creation - all values are snapshots at submission time.
 */
public interface TaskContext {

    /**
     * Get a request variable ($req.*).
     *
     * @param name Variable name (without prefix)
     * @return Variable value, or empty if not set
     */
    Optional<Object> getRequestVariable(String name);

    /**
     * Get all request variables.
     */
    Map<String, Object> getRequestVariables();

    /**
     * Get a context variable ($ctx.*).
     *
     * @param name Variable name (without prefix)
     * @return Variable value, or empty if not set
     */
    Optional<Object> getContextVariable(String name);

    /**
     * Get all context variables.
     */
    Map<String, Object> getContextVariables();

    /**
     * Get a system variable ($sys.*).
     * System variables are auto-populated by Pool.
     *
     * @param name Variable name (without prefix)
     * @return Variable value, or empty if not set
     */
    Optional<Object> getSystemVariable(String name);

    /**
     * Get all system variables.
     */
    Map<String, Object> getSystemVariables();

    /**
     * Get unique task identifier.
     */
    String getTaskId();

    /**
     * Get submission timestamp (epoch millis).
     */
    long getSubmittedAt();

    /**
     * Get correlation ID for tracing.
     */
    Optional<String> getCorrelationId();

    /**
     * Create a new builder.
     */
    static Builder builder() {
        return new DefaultTaskContext.Builder();
    }

    /**
     * Builder for TaskContext.
     */
    interface Builder {
        Builder taskId(String taskId);
        Builder correlationId(String correlationId);
        Builder requestVariable(String name, Object value);
        Builder requestVariables(Map<String, Object> variables);
        Builder contextVariable(String name, Object value);
        Builder contextVariables(Map<String, Object> variables);
        TaskContext build();
    }
}
