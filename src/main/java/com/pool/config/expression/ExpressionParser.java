package com.pool.config.expression;

import com.pool.condition.ConditionType;
import com.pool.config.ConditionConfig;
import com.pool.exception.ConfigurationException;

import java.util.ArrayList;
import java.util.List;

import static com.pool.config.expression.ExpressionConfig.*;

/**
 * Parser for condition expressions.
 * Converts tokens into a ConditionConfig tree using recursive descent parsing.
 * <p>
 * Grammar (precedence: NOT > AND > OR):
 * <pre>
 * expression := or
 * or         := and ('OR' and)*
 * and        := not ('AND' not)*
 * not        := 'NOT' not | primary
 * primary    := '(' expression ')' | comparison
 * comparison := field operator value
 * </pre>
 */
public final class ExpressionParser {

    private final String input;
    private final List<Token> tokens;
    private int index;

    public ExpressionParser(String input, List<Token> tokens) {
        this.input = input;
        this.tokens = tokens;
        this.index = 0;
    }

    /**
     * Parse the token stream into a ConditionConfig tree.
     *
     * @return Root condition configuration
     */
    public ConditionConfig parse() {
        ConditionConfig result = parseExpression();
        expect(TokenType.EOF);
        return result;
    }

    private ConditionConfig parseExpression() {
        return parseOr();
    }

    private ConditionConfig parseOr() {
        ConditionConfig left = parseAnd();
        List<ConditionConfig> conditions = new ArrayList<>();
        conditions.add(left);
        
        while (match(TokenType.OR)) {
            conditions.add(parseAnd());
        }
        
        return conditions.size() == 1 ? left : ConditionConfig.or(conditions);
    }

    private ConditionConfig parseAnd() {
        ConditionConfig left = parseNot();
        List<ConditionConfig> conditions = new ArrayList<>();
        conditions.add(left);
        
        while (match(TokenType.AND)) {
            conditions.add(parseNot());
        }
        
        return conditions.size() == 1 ? left : ConditionConfig.and(conditions);
    }

    private ConditionConfig parseNot() {
        if (match(TokenType.NOT)) {
            ConditionConfig inner = parseNot();
            return new ConditionConfig(ConditionType.NOT, null, null, null, null, null, List.of(inner));
        }
        return parsePrimary();
    }

    private ConditionConfig parsePrimary() {
        // Parenthesized expression
        if (match(TokenType.LPAREN)) {
            ConditionConfig expr = parseExpression();
            expect(TokenType.RPAREN);
            return expr;
        }
        
        // Boolean literal
        if (match(TokenType.BOOLEAN)) {
            boolean value = (boolean) previous().literal();
            if (value) {
                return ConditionConfig.alwaysTrue();
            }
            return new ConditionConfig(ConditionType.NOT, null, null, null, null, null,
                    List.of(ConditionConfig.alwaysTrue()));
        }
        
        return parseComparison();
    }

    private ConditionConfig parseComparison() {
        Token fieldToken = consume(TokenType.IDENT, "Expected field identifier");
        String field = normalizeField(fieldToken.text());

        // Existence checks
        if (match(TokenType.EXISTS)) {
            return new ConditionConfig(ConditionType.EXISTS, field, null, null, null, null, null);
        }
        if (match(TokenType.IS_NULL)) {
            return new ConditionConfig(ConditionType.IS_NULL, field, null, null, null, null, null);
        }

        // NOT IN
        if (match(TokenType.NOT)) {
            if (match(TokenType.IN)) {
                List<Object> values = parseList();
                return new ConditionConfig(ConditionType.NOT_IN, field, null, null, values, null, null);
            }
            throw error("Expected IN after NOT");
        }

        // IN
        if (match(TokenType.IN)) {
            List<Object> values = parseList();
            return new ConditionConfig(ConditionType.IN, field, null, null, values, null, null);
        }

        // String operators
        if (match(TokenType.REGEX)) {
            String pattern = parsePattern();
            return new ConditionConfig(ConditionType.REGEX, field, null, null, null, pattern, null);
        }
        if (match(TokenType.STARTS_WITH)) {
            String pattern = parsePattern();
            return new ConditionConfig(ConditionType.STARTS_WITH, field, null, null, null, pattern, null);
        }
        if (match(TokenType.ENDS_WITH)) {
            String pattern = parsePattern();
            return new ConditionConfig(ConditionType.ENDS_WITH, field, null, null, null, pattern, null);
        }
        if (match(TokenType.CONTAINS)) {
            Object value = parseValue();
            return new ConditionConfig(ConditionType.CONTAINS, field, value, null, null, null, null);
        }

        // Comparison operators
        if (match(TokenType.EQ)) {
            Object value = parseValue();
            return new ConditionConfig(ConditionType.EQUALS, field, value, null, null, null, null);
        }
        if (match(TokenType.NE)) {
            Object value = parseValue();
            return new ConditionConfig(ConditionType.NOT_EQUALS, field, value, null, null, null, null);
        }
        if (match(TokenType.GTE)) {
            Object value = parseNumericValue(">= requires a numeric value");
            return new ConditionConfig(ConditionType.GREATER_THAN_OR_EQUALS, field, value, null, null, null, null);
        }
        if (match(TokenType.GT)) {
            Object value = parseNumericValue("> requires a numeric value");
            return new ConditionConfig(ConditionType.GREATER_THAN, field, value, null, null, null, null);
        }
        if (match(TokenType.LTE)) {
            Object value = parseNumericValue("<= requires a numeric value");
            return new ConditionConfig(ConditionType.LESS_THAN_OR_EQUALS, field, value, null, null, null, null);
        }
        if (match(TokenType.LT)) {
            Object value = parseNumericValue("< requires a numeric value");
            return new ConditionConfig(ConditionType.LESS_THAN, field, value, null, null, null, null);
        }

        throw error("Expected operator after field");
    }

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
        return new ConfigurationException("Invalid condition-expr at position "
                + position + ": " + message + " in '" + input + "'");
    }

    private String normalizeField(String field) {
        if (field.startsWith(SYSTEM_PREFIX) || field.startsWith(REQUEST_PREFIX)) {
            return field;
        }
        return REQUEST_PREFIX + field;
    }
}
