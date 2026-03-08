package com.pool.expression;

import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

/**
 * Expression evaluator that uses the AST-based parser.
 * Parses expressions into an Expression tree, then evaluates against a context.
 * <p>
 * Supports:
 * - Logical: AND, OR, NOT
 * - Comparison: ==, !=, >, >=, <, <=
 * - Collection: IN, NOT IN, CONTAINS
 * - String: REGEX, STARTS_WITH, ENDS_WITH
 * - Existence: EXISTS, IS_NULL
 * - Parentheses for grouping
 * - Boolean literals: true, false
 */
public class ExpressionEvaluator {

    private final VariableResolver variableResolver;

    public ExpressionEvaluator(VariableResolver variableResolver) {
        this.variableResolver = variableResolver;
    }

    /**
     * Evaluate an expression against a task context.
     *
     * @param expression Expression string (e.g., '$req.region == "US" AND $req.amount > 100')
     * @param context    Task context containing variables
     * @return true if expression evaluates to true
     */
    public boolean evaluate(String expression, TaskContext context) {
        Expression expr = parse(expression);
        return expr.evaluate(context, variableResolver);
    }

    /**
     * Parse an expression string into a reusable Expression AST.
     * The returned Expression can be evaluated multiple times against different contexts.
     *
     * @param expression Expression string
     * @return Parsed Expression tree
     */
    public Expression parse(String expression) {
        return new ExpressionParser(expression).parse();
    }
}
