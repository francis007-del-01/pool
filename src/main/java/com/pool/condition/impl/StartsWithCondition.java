package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Optional;

/**
 * Condition that checks if a field value starts with a specific prefix.
 */
public class StartsWithCondition implements Condition {

    private final String field;
    private final String prefix;
    private final VariableResolver resolver;

    public StartsWithCondition(String field, String prefix, VariableResolver resolver) {
        this.field = field;
        this.prefix = prefix;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }

        String value = String.valueOf(actual.get());
        return value.startsWith(prefix);
    }

    @Override
    public ConditionType getType() {
        return ConditionType.STARTS_WITH;
    }

    @Override
    public String toString() {
        return field + " STARTS_WITH '" + prefix + "'";
    }
}
