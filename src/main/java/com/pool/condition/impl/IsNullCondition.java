package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Optional;

/**
 * Condition that checks if a field is null or not present.
 */
public class IsNullCondition implements Condition {

    private final String field;
    private final VariableResolver resolver;

    public IsNullCondition(String field, VariableResolver resolver) {
        this.field = field;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        return actual.isEmpty();
    }

    @Override
    public ConditionType getType() {
        return ConditionType.IS_NULL;
    }

    @Override
    public String toString() {
        return field + " IS NULL";
    }
}
