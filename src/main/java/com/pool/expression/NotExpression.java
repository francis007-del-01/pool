package com.pool.expression;

import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

/**
 * Logical NOT expression: negates the inner expression.
 */
public class NotExpression implements Expression {

    private final Expression inner;

    public NotExpression(Expression inner) {
        this.inner = inner;
    }

    @Override
    public boolean evaluate(TaskContext context, VariableResolver variableResolver) {
        return !inner.evaluate(context, variableResolver);
    }

    @Override
    public String toString() {
        return "(NOT " + inner + ")";
    }
}
