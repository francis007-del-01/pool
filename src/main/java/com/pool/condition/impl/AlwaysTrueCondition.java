package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;

/**
 * Condition that always evaluates to true.
 * Used as default/catch-all in priority tree.
 */
public class AlwaysTrueCondition implements Condition {

    public static final AlwaysTrueCondition INSTANCE = new AlwaysTrueCondition();

    private AlwaysTrueCondition() {}

    @Override
    public boolean evaluate(TaskContext context) {
        return true;
    }

    @Override
    public ConditionType getType() {
        return ConditionType.ALWAYS_TRUE;
    }

    @Override
    public String toString() {
        return "ALWAYS_TRUE";
    }
}
