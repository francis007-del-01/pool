package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;

import java.util.List;

/**
 * Logical OR condition - at least one nested condition must be true.
 */
public class OrCondition implements Condition {

    private final List<Condition> conditions;

    public OrCondition(List<Condition> conditions) {
        this.conditions = conditions;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        if (conditions == null || conditions.isEmpty()) {
            return false; // Empty OR is false
        }
        return conditions.stream().anyMatch(c -> c.evaluate(context));
    }

    @Override
    public ConditionType getType() {
        return ConditionType.OR;
    }

    @Override
    public String toString() {
        return "OR(" + conditions + ")";
    }
}
