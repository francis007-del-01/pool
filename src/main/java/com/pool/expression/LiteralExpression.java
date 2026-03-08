package com.pool.expression;

import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

/**
 * Literal boolean expression: always evaluates to a fixed value (true/false).
 */
public class LiteralExpression implements Expression {

    private final boolean value;

    public LiteralExpression(boolean value) {
        this.value = value;
    }

    @Override
    public boolean evaluate(TaskContext context, VariableResolver variableResolver) {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
