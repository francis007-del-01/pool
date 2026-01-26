package com.pool.condition;

import com.pool.config.ConditionConfig;
import com.pool.core.TaskContext;

/**
 * Factory and evaluator for conditions.
 */
public interface ConditionEvaluator {

    /**
     * Create a Condition instance from configuration.
     *
     * @param config Condition configuration
     * @return Condition instance
     */
    Condition create(ConditionConfig config);

    /**
     * Evaluate a condition configuration directly against a context.
     *
     * @param config  Condition configuration
     * @param context Task context
     * @return true if condition matches, false otherwise
     */
    default boolean evaluate(ConditionConfig config, TaskContext context) {
        return create(config).evaluate(context);
    }
}
