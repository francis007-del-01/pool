package com.pool.expression;

import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

/**
 * Logical OR expression: evaluates to true if either left or right is true.
 */
public class OrExpression implements Expression {

    private final Expression left;
    private final Expression right;

    public OrExpression(Expression left, Expression right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean evaluate(TaskContext context, VariableResolver variableResolver) {
        return left.evaluate(context, variableResolver) || right.evaluate(context, variableResolver);
    }

    @Override
    public String toString() {
        return "(" + left + " OR " + right + ")";
    }
}
