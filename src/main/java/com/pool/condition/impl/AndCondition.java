package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;

import java.util.List;

/**
 * Logical AND condition - all nested conditions must be true.
 */
public class AndCondition implements Condition {

    private final List<Condition> conditions;

    public AndCondition(List<Condition> conditions) {
        this.conditions = conditions;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        if (conditions == null || conditions.isEmpty()) {
            return true; // Empty AND is true
        }
        return conditions.stream().allMatch(c -> c.evaluate(context));
    }

    @Override
    public ConditionType getType() {
        return ConditionType.AND;
    }

    @Override
    public String toString() {
        return "AND(" + conditions + ")";
    }
}
