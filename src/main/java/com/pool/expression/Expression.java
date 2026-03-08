package com.pool.expression;

import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

/**
 * Represents a parsed expression node in the AST.
 * Can be evaluated against a TaskContext to produce a boolean result.
 */
public interface Expression {

    /**
     * Evaluate this expression against the given context.
     *
     * @param context          Task context containing variables
     * @param variableResolver Resolver for variable lookups
     * @return true if the expression evaluates to true
     */
    boolean evaluate(TaskContext context, VariableResolver variableResolver);
}
