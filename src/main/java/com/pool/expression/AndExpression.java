package com.pool.expression;

import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

/**
 * Logical AND expression: evaluates to true only if both left and right are true.
 */
public class AndExpression implements Expression {

    private final Expression left;
    private final Expression right;

    public AndExpression(Expression left, Expression right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean evaluate(TaskContext context, VariableResolver variableResolver) {
        return left.evaluate(context, variableResolver) && right.evaluate(context, variableResolver);
    }

    @Override
    public String toString() {
        return "(" + left + " AND " + right + ")";
    }
}
