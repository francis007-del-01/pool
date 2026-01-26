package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.List;
import java.util.Optional;

/**
 * Condition that checks if a field value is in a list of allowed values.
 */
public class InCondition implements Condition {

    private final String field;
    private final List<Object> allowedValues;
    private final VariableResolver resolver;

    public InCondition(String field, List<Object> allowedValues, VariableResolver resolver) {
        this.field = field;
        this.allowedValues = allowedValues;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }

        Object value = actual.get();
        for (Object allowed : allowedValues) {
            if (valuesMatch(value, allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean valuesMatch(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual.equals(expected)) {
            return true;
        }
        if (actual instanceof Number && expected instanceof Number) {
            return ((Number) actual).doubleValue() == ((Number) expected).doubleValue();
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    @Override
    public ConditionType getType() {
        return ConditionType.IN;
    }

    @Override
    public String toString() {
        return field + " IN " + allowedValues;
    }
}
