package com.pool.policy;

import com.pool.core.TaskContext;

/**
 * Evaluates business rules and calculates task priority.
 * Called once at task submission time (static priority model).
 */
public interface PolicyEngine {

    /**
     * Evaluate the priority tree and calculate priority for the given context.
     *
     * @param context Task context with all variables
     * @return Evaluation result containing matched path and priority key
     */
    EvaluationResult evaluate(TaskContext context);

    /**
     * Reload policy configuration.
     */
    void reload();
}
