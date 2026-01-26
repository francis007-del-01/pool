package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Optional;

/**
 * Base class for numeric comparison conditions (>, >=, <, <=).
 */
public class ComparisonCondition implements Condition {

    private final String field;
    private final Number threshold;
    private final ConditionType type;
    private final VariableResolver resolver;

    public ComparisonCondition(String field, Number threshold, ConditionType type, VariableResolver resolver) {
        this.field = field;
        this.threshold = threshold;
        this.type = type;
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Double> actual = resolver.resolveAsDouble(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }

        double actualValue = actual.get();
        double thresholdValue = threshold.doubleValue();

        return switch (type) {
            case GREATER_THAN -> actualValue > thresholdValue;
            case GREATER_THAN_OR_EQUALS -> actualValue >= thresholdValue;
            case LESS_THAN -> actualValue < thresholdValue;
            case LESS_THAN_OR_EQUALS -> actualValue <= thresholdValue;
            default -> throw new IllegalStateException("Invalid comparison type: " + type);
        };
    }

    @Override
    public ConditionType getType() {
        return type;
    }

    @Override
    public String toString() {
        String op = switch (type) {
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUALS -> ">=";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUALS -> "<=";
            default -> "?";
        };
        return field + " " + op + " " + threshold;
    }

    // Factory methods
    public static ComparisonCondition greaterThan(String field, Number threshold, VariableResolver resolver) {
        return new ComparisonCondition(field, threshold, ConditionType.GREATER_THAN, resolver);
    }

    public static ComparisonCondition greaterThanOrEquals(String field, Number threshold, VariableResolver resolver) {
        return new ComparisonCondition(field, threshold, ConditionType.GREATER_THAN_OR_EQUALS, resolver);
    }

    public static ComparisonCondition lessThan(String field, Number threshold, VariableResolver resolver) {
        return new ComparisonCondition(field, threshold, ConditionType.LESS_THAN, resolver);
    }

    public static ComparisonCondition lessThanOrEquals(String field, Number threshold, VariableResolver resolver) {
        return new ComparisonCondition(field, threshold, ConditionType.LESS_THAN_OR_EQUALS, resolver);
    }
}
