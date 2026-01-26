package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;

/**
 * Logical NOT condition - negates the nested condition.
 */
public class NotCondition implements Condition {

    private final Condition condition;

    public NotCondition(Condition condition) {
        this.condition = condition;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        return !condition.evaluate(context);
    }

    @Override
    public ConditionType getType() {
        return ConditionType.NOT;
    }

    @Override
    public String toString() {
        return "NOT(" + condition + ")";
    }
}
