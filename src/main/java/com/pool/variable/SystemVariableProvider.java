package com.pool.variable;

/**
 * Provides a single system variable computed at runtime.
 * Each implementation handles one specific system variable.
 */
public interface SystemVariableProvider {

    /**
     * Get the name of this system variable (without $sys. prefix).
     * e.g., "time.now", "taskId", "submittedAt"
     *
     * @return Variable name
     */
    String getName();

    /**
     * Compute the value of this system variable.
     *
     * @param context Context for computing the value (provides taskId, submittedAt, etc.)
     * @return The computed value
     */
    Object getValue(SystemVariableContext context);
}
