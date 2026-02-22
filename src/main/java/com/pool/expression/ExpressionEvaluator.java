package com.pool.expression;

import com.pool.config.expression.ExpressionTokenizer;
import com.pool.config.expression.Token;
import com.pool.config.expression.TokenType;
import com.pool.core.TaskContext;
import com.pool.exception.ConfigurationException;
import com.pool.variable.VariableResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.pool.config.expression.ExpressionConfig.*;

/**
 * Direct expression evaluator that evaluates boolean expressions at runtime.
 * Combines parsing and evaluation in one step - no intermediate ConditionConfig needed.
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
        if (expression == null || expression.isBlank()) {
            return true; // Empty expression = always true
        }

        List<Token> tokens = new ExpressionTokenizer(expression).tokenize();
        Parser parser = new Parser(expression, tokens, context);
        return parser.parse();
    }

    /**
     * Internal parser that evaluates during parsing.
     */
    private class Parser {
        private final String input;
        private final List<Token> tokens;
        private final TaskContext context;
        private int index;

        Parser(String input, List<Token> tokens, TaskContext context) {
            this.input = input;
            this.tokens = tokens;
            this.context = context;
            this.index = 0;
        }

        boolean parse() {
            boolean result = parseExpression();
            expect(TokenType.EOF);
            return result;
        }

        private boolean parseExpression() {
            return parseOr();
        }

        private boolean parseOr() {
            boolean left = parseAnd();
            while (match(TokenType.OR)) {
                boolean right = parseAnd();
                left = left || right;
            }
            return left;
        }

        private boolean parseAnd() {
            boolean left = parseNot();
            while (match(TokenType.AND)) {
                boolean right = parseNot();
                left = left && right;
            }
            return left;
        }

        private boolean parseNot() {
            if (match(TokenType.NOT)) {
                return !parseNot();
            }
            return parsePrimary();
        }

        private boolean parsePrimary() {
            // Parenthesized expression
            if (match(TokenType.LPAREN)) {
                boolean expr = parseExpression();
                expect(TokenType.RPAREN);
                return expr;
            }

            // Boolean literal
            if (match(TokenType.BOOLEAN)) {
                return (boolean) previous().literal();
            }

            return parseComparison();
        }

        private boolean parseComparison() {
            Token fieldToken = consume(TokenType.IDENT, "Expected field identifier");
            String field = normalizeField(fieldToken.text());

            // Existence checks
            if (match(TokenType.EXISTS)) {
                return variableResolver.resolve(field, context).isPresent();
            }
            if (match(TokenType.IS_NULL)) {
                Optional<Object> val = variableResolver.resolve(field, context);
                return val.isEmpty() || val.get() == null;
            }

            // NOT IN
            if (match(TokenType.NOT)) {
                if (match(TokenType.IN)) {
                    List<Object> values = parseList();
                    return !evaluateIn(field, values);
                }
                throw error("Expected IN after NOT");
            }

            // IN
            if (match(TokenType.IN)) {
                List<Object> values = parseList();
                return evaluateIn(field, values);
            }

            // String operators
            if (match(TokenType.REGEX)) {
                String pattern = parsePattern();
                return evaluateRegex(field, pattern);
            }
            if (match(TokenType.STARTS_WITH)) {
                String pattern = parsePattern();
                return evaluateStartsWith(field, pattern);
            }
            if (match(TokenType.ENDS_WITH)) {
                String pattern = parsePattern();
                return evaluateEndsWith(field, pattern);
            }
            if (match(TokenType.CONTAINS)) {
                Object value = parseValue();
                return evaluateContains(field, value);
            }

            // Comparison operators
            if (match(TokenType.EQ)) {
                Object value = parseValue();
                return evaluateEquals(field, value);
            }
            if (match(TokenType.NE)) {
                Object value = parseValue();
                return !evaluateEquals(field, value);
            }
            if (match(TokenType.GTE)) {
                Object value = parseNumericValue(">= requires a numeric value");
                return evaluateComparison(field, (Number) value) >= 0;
            }
            if (match(TokenType.GT)) {
                Object value = parseNumericValue("> requires a numeric value");
                return evaluateComparison(field, (Number) value) > 0;
            }
            if (match(TokenType.LTE)) {
                Object value = parseNumericValue("<= requires a numeric value");
                return evaluateComparison(field, (Number) value) <= 0;
            }
            if (match(TokenType.LT)) {
                Object value = parseNumericValue("< requires a numeric value");
                return evaluateComparison(field, (Number) value) < 0;
            }

            throw error("Expected operator after field");
        }

        // Evaluation methods

        private boolean evaluateEquals(String field, Object expected) {
            Optional<Object> actual = variableResolver.resolve(field, context);
            if (actual.isEmpty()) {
                return false;
            }
            return compareValues(actual.get(), expected);
        }

        private int evaluateComparison(String field, Number threshold) {
            Optional<Object> actual = variableResolver.resolve(field, context);
            if (actual.isEmpty()) {
                return -1; // Missing = less than threshold
            }
            Object val = actual.get();
            if (val instanceof Number num) {
                return Double.compare(num.doubleValue(), threshold.doubleValue());
            }
            // Try to parse as number
            try {
                double d = Double.parseDouble(val.toString());
                return Double.compare(d, threshold.doubleValue());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private boolean evaluateIn(String field, List<Object> values) {
            Optional<Object> actual = variableResolver.resolve(field, context);
            if (actual.isEmpty()) {
                return false;
            }
            Object val = actual.get();
            for (Object expected : values) {
                if (compareValues(val, expected)) {
                    return true;
                }
            }
            return false;
        }

        private boolean evaluateContains(String field, Object value) {
            Optional<Object> actual = variableResolver.resolve(field, context);
            if (actual.isEmpty()) {
                return false;
            }
            Object val = actual.get();
            if (val instanceof List<?> list) {
                for (Object item : list) {
                    if (compareValues(item, value)) {
                        return true;
                    }
                }
                return false;
            }
            // String contains
            return val.toString().contains(value.toString());
        }

        private boolean evaluateRegex(String field, String pattern) {
            Optional<Object> actual = variableResolver.resolve(field, context);
            if (actual.isEmpty()) {
                return false;
            }
            return Pattern.matches(pattern, actual.get().toString());
        }

        private boolean evaluateStartsWith(String field, String prefix) {
            Optional<Object> actual = variableResolver.resolve(field, context);
            if (actual.isEmpty()) {
                return false;
            }
            return actual.get().toString().startsWith(prefix);
        }

        private boolean evaluateEndsWith(String field, String suffix) {
            Optional<Object> actual = variableResolver.resolve(field, context);
            if (actual.isEmpty()) {
                return false;
            }
            return actual.get().toString().endsWith(suffix);
        }

        private boolean compareValues(Object actual, Object expected) {
            if (Objects.equals(actual, expected)) {
                return true;
            }
            // Handle numeric comparisons across types
            if (actual instanceof Number && expected instanceof Number) {
                return ((Number) actual).doubleValue() == ((Number) expected).doubleValue();
            }
            // Handle string comparison
            return String.valueOf(actual).equals(String.valueOf(expected));
        }

        // Parsing helpers

        private List<Object> parseList() {
            boolean bracket = match(TokenType.LBRACKET);
            if (!bracket) {
                expect(TokenType.LPAREN);
            }

            List<Object> values = new ArrayList<>();
            if (!check(bracket ? TokenType.RBRACKET : TokenType.RPAREN)) {
                values.add(parseValue());
                while (match(TokenType.COMMA)) {
                    values.add(parseValue());
                }
            }

            expect(bracket ? TokenType.RBRACKET : TokenType.RPAREN);
            return values;
        }

        private Object parseValue() {
            if (match(TokenType.STRING, TokenType.NUMBER, TokenType.BOOLEAN, TokenType.NULL, TokenType.IDENT)) {
                return previous().literal();
            }
            throw error("Expected value");
        }

        private Object parseNumericValue(String message) {
            if (match(TokenType.NUMBER)) {
                return previous().literal();
            }
            throw error(message);
        }

        private String parsePattern() {
            if (match(TokenType.STRING, TokenType.IDENT)) {
                Object literal = previous().literal();
                return literal == null ? null : literal.toString();
            }
            throw error("Expected pattern");
        }

        private boolean match(TokenType... types) {
            for (TokenType type : types) {
                if (check(type)) {
                    advance();
                    return true;
                }
            }
            return false;
        }

        private Token consume(TokenType type, String message) {
            if (check(type)) {
                return advance();
            }
            throw error(message);
        }

        private void expect(TokenType type) {
            if (!check(type)) {
                throw error("Expected " + type);
            }
            advance();
        }

        private boolean check(TokenType type) {
            return peek().type() == type;
        }

        private Token advance() {
            if (!isAtEnd()) {
                index++;
            }
            return previous();
        }

        private boolean isAtEnd() {
            return peek().type() == TokenType.EOF;
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token previous() {
            return tokens.get(index - 1);
        }

        private ConfigurationException error(String message) {
            int position = peek().position();
            return new ConfigurationException("Invalid expression at position "
                    + position + ": " + message + " in '" + input + "'");
        }

        private String normalizeField(String field) {
            if (field.startsWith(SYSTEM_PREFIX) || field.startsWith(REQUEST_PREFIX)) {
                return field;
            }
            return REQUEST_PREFIX + field;
        }
    }
}
