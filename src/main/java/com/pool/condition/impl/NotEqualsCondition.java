package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Objects;
import java.util.Optional;

/**
 * Condition that checks if a field does not equal a specific value.
 */
public class NotEqualsCondition implements Condition {

    private final String field;
    private final Object expectedValue;
    private final VariableResolver resolver;

    public NotEqualsCondition(String field, Object expectedValue, VariableResolver resolver) {
        this.field = field;
        this.expectedValue = expectedValue;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }
        return !compareValues(actual.get(), expectedValue);
    }

    private boolean compareValues(Object actual, Object expected) {
        if (Objects.equals(actual, expected)) {
            return true;
        }
        if (actual instanceof Number && expected instanceof Number) {
            return ((Number) actual).doubleValue() == ((Number) expected).doubleValue();
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    @Override
    public ConditionType getType() {
        return ConditionType.NOT_EQUALS;
    }

    @Override
    public String toString() {
        return field + " != " + expectedValue;
    }
}
