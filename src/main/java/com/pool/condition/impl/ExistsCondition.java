package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Optional;

/**
 * Condition that checks if a field exists (is present and not null).
 */
public class ExistsCondition implements Condition {

    private final String field;
    private final VariableResolver resolver;

    public ExistsCondition(String field, VariableResolver resolver) {
        this.field = field;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        return actual.isPresent();
    }

    @Override
    public ConditionType getType() {
        return ConditionType.EXISTS;
    }

    @Override
    public String toString() {
        return field + " EXISTS";
    }
}
