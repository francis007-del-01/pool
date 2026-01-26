package com.pool.condition;

import com.pool.core.TaskContext;

/**
 * Represents a boolean condition that can be evaluated against a task context.
 */
public interface Condition {

    /**
     * Evaluate this condition against the given context.
     *
     * @param context Task context containing variables
     * @return true if condition matches, false otherwise
     */
    boolean evaluate(TaskContext context);

    /**
     * Get the condition type.
     */
    ConditionType getType();
}
