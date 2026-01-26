package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Optional;

/**
 * Condition that checks if a field value is between two bounds (inclusive).
 */
public class BetweenCondition implements Condition {

    private final String field;
    private final Number lowerBound;
    private final Number upperBound;
    private final VariableResolver resolver;

    public BetweenCondition(String field, Number lowerBound, Number upperBound, VariableResolver resolver) {
        this.field = field;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Double> actual = resolver.resolveAsDouble(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }

        double value = actual.get();
        return value >= lowerBound.doubleValue() && value <= upperBound.doubleValue();
    }

    @Override
    public ConditionType getType() {
        return ConditionType.BETWEEN;
    }

    @Override
    public String toString() {
        return lowerBound + " <= " + field + " <= " + upperBound;
    }
}
