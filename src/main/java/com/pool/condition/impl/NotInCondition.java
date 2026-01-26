package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.List;
import java.util.Optional;

/**
 * Condition that checks if a field value is NOT in a list of values.
 */
public class NotInCondition implements Condition {

    private final String field;
    private final List<Object> excludedValues;
    private final VariableResolver resolver;

    public NotInCondition(String field, List<Object> excludedValues, VariableResolver resolver) {
        this.field = field;
        this.excludedValues = excludedValues;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }

        Object value = actual.get();
        for (Object excluded : excludedValues) {
            if (valuesMatch(value, excluded)) {
                return false;
            }
        }
        return true;
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
        return ConditionType.NOT_IN;
    }

    @Override
    public String toString() {
        return field + " NOT IN " + excludedValues;
    }
}
