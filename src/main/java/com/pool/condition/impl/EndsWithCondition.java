package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Optional;

/**
 * Condition that checks if a field value ends with a specific suffix.
 */
public class EndsWithCondition implements Condition {

    private final String field;
    private final String suffix;
    private final VariableResolver resolver;

    public EndsWithCondition(String field, String suffix, VariableResolver resolver) {
        this.field = field;
        this.suffix = suffix;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }

        String value = String.valueOf(actual.get());
        return value.endsWith(suffix);
    }

    @Override
    public ConditionType getType() {
        return ConditionType.ENDS_WITH;
    }

    @Override
    public String toString() {
        return field + " ENDS_WITH '" + suffix + "'";
    }
}
