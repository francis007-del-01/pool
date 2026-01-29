package com.pool.config;

import com.pool.config.expression.ExpressionParser;
import com.pool.config.expression.ExpressionTokenizer;
import com.pool.config.expression.Token;

import java.util.List;

/**
 * Facade for parsing flat boolean expressions into ConditionConfig trees.
 * <p>
 * Supports:
 * <ul>
 *   <li>Logical operators: AND, OR, NOT</li>
 *   <li>Comparisons: ==, =, !=, >, >=, <, <=</li>
 *   <li>Collection: IN, NOT IN, CONTAINS</li>
 *   <li>String: REGEX, STARTS_WITH, ENDS_WITH</li>
 *   <li>Existence: EXISTS, IS_NULL</li>
 *   <li>Parentheses for grouping</li>
 * </ul>
 * <p>
 * Precedence: NOT > AND > OR (parentheses override)
 */
public final class ConditionExpressionParser {

    private ConditionExpressionParser() {
    }

    /**
     * Parse a condition expression into a ConditionConfig tree.
     *
     * @param expression Expression string
     * @return Parsed condition configuration
     */
    public static ConditionConfig parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return ConditionConfig.alwaysTrue();
        }

        // Tokenize
        ExpressionTokenizer tokenizer = new ExpressionTokenizer(expression);
        List<Token> tokens = tokenizer.tokenize();

        // Parse
        ExpressionParser parser = new ExpressionParser(expression, tokens);
        return parser.parse();
    }
}
