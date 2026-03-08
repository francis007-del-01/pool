package com.pool.expression;

import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Leaf expression node: evaluates a single comparison operation
 * (e.g., $req.region == "US", $req.amount > 100).
 */
public class ComparisonExpression implements Expression {

    private final String field;
    private final Operator operator;
    private final Object value;
    private final List<Object> listValue;

    /**
     * Supported comparison operators.
     */
    public enum Operator {
        EQ, NE, GT, GTE, LT, LTE,
        IN, NOT_IN, CONTAINS,
        REGEX, STARTS_WITH, ENDS_WITH,
        EXISTS, IS_NULL
    }

    /**
     * Constructor for single-value comparisons.
     */
    public ComparisonExpression(String field, Operator operator, Object value) {
        this.field = field;
        this.operator = operator;
        this.value = value;
        this.listValue = null;
    }

    /**
     * Constructor for list-value comparisons (IN, NOT_IN).
     */
    public ComparisonExpression(String field, Operator operator, List<Object> listValue) {
        this.field = field;
        this.operator = operator;
        this.value = null;
        this.listValue = listValue;
    }

    /**
     * Constructor for unary operators (EXISTS, IS_NULL).
     */
    public ComparisonExpression(String field, Operator operator) {
        this.field = field;
        this.operator = operator;
        this.value = null;
        this.listValue = null;
    }

    @Override
    public boolean evaluate(TaskContext context, VariableResolver variableResolver) {
        return switch (operator) {
            case EQ -> evaluateEquals(context, variableResolver);
            case NE -> !evaluateEquals(context, variableResolver);
            case GT -> evaluateComparison(context, variableResolver) > 0;
            case GTE -> evaluateComparison(context, variableResolver) >= 0;
            case LT -> evaluateComparison(context, variableResolver) < 0;
            case LTE -> evaluateComparison(context, variableResolver) <= 0;
            case IN -> evaluateIn(context, variableResolver);
            case NOT_IN -> !evaluateIn(context, variableResolver);
            case CONTAINS -> evaluateContains(context, variableResolver);
            case REGEX -> evaluateRegex(context, variableResolver);
            case STARTS_WITH -> evaluateStartsWith(context, variableResolver);
            case ENDS_WITH -> evaluateEndsWith(context, variableResolver);
            case EXISTS -> variableResolver.resolve(field, context).isPresent();
            case IS_NULL -> {
                Optional<Object> val = variableResolver.resolve(field, context);
                yield val.isEmpty() || val.get() == null;
            }
        };
    }

    private boolean evaluateEquals(TaskContext context, VariableResolver variableResolver) {
        Optional<Object> actual = variableResolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false;
        }
        return compareValues(actual.get(), value);
    }

    private int evaluateComparison(TaskContext context, VariableResolver variableResolver) {
        Optional<Object> actual = variableResolver.resolve(field, context);
        if (actual.isEmpty()) {
            return -1;
        }
        Object val = actual.get();
        if (val instanceof Number num && value instanceof Number threshold) {
            return Double.compare(num.doubleValue(), threshold.doubleValue());
        }
        try {
            double d = Double.parseDouble(val.toString());
            return Double.compare(d, ((Number) value).doubleValue());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean evaluateIn(TaskContext context, VariableResolver variableResolver) {
        Optional<Object> actual = variableResolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false;
        }
        Object val = actual.get();
        for (Object expected : listValue) {
            if (compareValues(val, expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateContains(TaskContext context, VariableResolver variableResolver) {
        Optional<Object> actual = variableResolver.resolve(field, context);
        if (actual.isEmpty()) return false;
        Object val = actual.get();
        if (val instanceof List<?> list) {
            for (Object item : list) {
                if (compareValues(item, value)) return true;
            }
            return false;
        }
        return val.toString().contains(value.toString());
    }

    private boolean evaluateRegex(TaskContext context, VariableResolver variableResolver) {
        Optional<Object> actual = variableResolver.resolve(field, context);
        if (actual.isEmpty()) return false;
        return Pattern.matches(value.toString(), actual.get().toString());
    }

    private boolean evaluateStartsWith(TaskContext context, VariableResolver variableResolver) {
        Optional<Object> actual = variableResolver.resolve(field, context);
        if (actual.isEmpty()) return false;
        return actual.get().toString().startsWith(value.toString());
    }

    private boolean evaluateEndsWith(TaskContext context, VariableResolver variableResolver) {
        Optional<Object> actual = variableResolver.resolve(field, context);
        if (actual.isEmpty()) return false;
        return actual.get().toString().endsWith(value.toString());
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
    public String toString() {
        if (operator == Operator.EXISTS || operator == Operator.IS_NULL) {
            return field + " " + operator;
        }
        if (listValue != null) {
            return field + " " + operator + " " + listValue;
        }
        return field + " " + operator + " " + value;
    }
}
