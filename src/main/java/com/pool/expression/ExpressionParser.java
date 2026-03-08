package com.pool.expression;

import com.pool.exception.ConfigurationException;
import com.pool.variable.VariableSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser that builds an Expression AST directly from the input string.
 * No separate tokenizer — scans characters as needed during parsing.
 * <p>
 * Operator precedence (lowest to highest):
 * 1. OR
 * 2. AND
 * 3. NOT
 * 4. Primary (parenthesized groups, boolean literals, comparisons)
 */
public class ExpressionParser {

    // Keyword constants
    private static final String KW_AND = "AND";
    private static final String KW_OR = "OR";
    private static final String KW_NOT = "NOT";
    private static final String KW_IN = "IN";
    private static final String KW_EXISTS = "EXISTS";
    private static final String KW_IS_NULL = "IS_NULL";
    private static final String KW_CONTAINS = "CONTAINS";
    private static final String KW_REGEX = "REGEX";
    private static final String KW_STARTS_WITH = "STARTS_WITH";
    private static final String KW_ENDS_WITH = "ENDS_WITH";

    private static final String KW_TRUE = "true";
    private static final String KW_FALSE = "false";
    private static final String KW_NULL = "null";

    private final String input;
    private int pos;

    public ExpressionParser(String input) {
        this.input = input;
        this.pos = 0;
    }

    /**
     * Parse the full expression into an AST.
     */
    public Expression parse() {
        if (input == null || input.isBlank()) {
            return new LiteralExpression(true);
        }

        Expression expr = parseOr();
        skipWhitespace();
        if (!isAtEnd()) {
            throw error("Unexpected input");
        }
        return expr;
    }

    // ========== Recursive Descent ==========

    private Expression parseOr() {
        Expression left = parseAnd();
        while (matchKeyword(KW_OR)) {
            Expression right = parseAnd();
            left = new OrExpression(left, right);
        }
        return left;
    }

    private Expression parseAnd() {
        Expression left = parseNot();
        while (matchKeyword(KW_AND)) {
            Expression right = parseNot();
            left = new AndExpression(left, right);
        }
        return left;
    }

    private Expression parseNot() {
        if (matchKeyword(KW_NOT)) {
            Expression inner = parseNot();
            return new NotExpression(inner);
        }
        return parsePrimary();
    }

    private Expression parsePrimary() {
        skipWhitespace();

        // Parenthesized expression
        if (matchChar('(')) {
            Expression expr = parseOr();
            expectChar(')');
            return expr;
        }

        // Boolean literals
        if (matchKeyword(KW_TRUE)) {
            return new LiteralExpression(true);
        }
        if (matchKeyword(KW_FALSE)) {
            return new LiteralExpression(false);
        }

        // Comparison expression (leaf)
        return parseComparison();
    }

    private Expression parseComparison() {
        String field = normalizeField(readIdentifier());

        skipWhitespace();

        // Unary operators
        if (matchKeyword(KW_EXISTS)) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.EXISTS);
        }
        if (matchKeyword(KW_IS_NULL)) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.IS_NULL);
        }

        // NOT IN
        if (matchKeyword(KW_NOT)) {
            skipWhitespace();
            if (matchKeyword(KW_IN)) {
                return new ComparisonExpression(field, ComparisonExpression.Operator.NOT_IN, parseList());
            }
            throw error("Expected " + KW_IN + " after " + KW_NOT);
        }

        // IN
        if (matchKeyword(KW_IN)) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.IN, parseList());
        }

        // String operators
        if (matchKeyword(KW_CONTAINS)) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.CONTAINS, readValue());
        }
        if (matchKeyword(KW_REGEX)) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.REGEX, readStringLiteral());
        }
        if (matchKeyword(KW_STARTS_WITH)) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.STARTS_WITH, readStringLiteral());
        }
        if (matchKeyword(KW_ENDS_WITH)) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.ENDS_WITH, readStringLiteral());
        }

        // Comparison operators
        if (matchOp("==") || matchOp("=")) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.EQ, readValue());
        }
        if (matchOp("!=")) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.NE, readValue());
        }
        if (matchOp(">=")) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.GTE, readNumber());
        }
        if (matchOp(">")) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.GT, readNumber());
        }
        if (matchOp("<=")) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.LTE, readNumber());
        }
        if (matchOp("<")) {
            return new ComparisonExpression(field, ComparisonExpression.Operator.LT, readNumber());
        }

        throw error("Expected operator after field '" + field + "'");
    }

    // ========== Value Readers ==========

    private Object readValue() {
        skipWhitespace();
        if (isAtEnd()) throw error("Expected value");

        char c = peek();
        if (c == '"' || c == '\'') return readStringLiteral();
        if (Character.isDigit(c) || c == '-') return readNumber();
        if (matchKeyword(KW_TRUE)) return true;
        if (matchKeyword(KW_FALSE)) return false;
        if (matchKeyword(KW_NULL)) return null;
        // Bare identifier as string value
        return readIdentifier();
    }

    private String readStringLiteral() {
        skipWhitespace();
        if (isAtEnd()) throw error("Expected string");

        char quote = peek();
        if (quote != '"' && quote != '\'') throw error("Expected quoted string");
        pos++; // skip opening quote

        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && peek() != quote) {
            char c = advance();
            if (c == '\\' && !isAtEnd()) {
                char escaped = advance();
                switch (escaped) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(escaped);
                }
            } else {
                sb.append(c);
            }
        }

        if (isAtEnd()) throw error("Unterminated string");
        pos++; // skip closing quote
        return sb.toString();
    }

    private Number readNumber() {
        skipWhitespace();
        int start = pos;
        if (!isAtEnd() && peek() == '-') pos++;
        while (!isAtEnd() && Character.isDigit(peek())) pos++;
        if (!isAtEnd() && peek() == '.') {
            pos++;
            while (!isAtEnd() && Character.isDigit(peek())) pos++;
        }

        String text = input.substring(start, pos);
        if (text.isEmpty() || text.equals("-")) throw error("Expected number");

        try {
            return text.contains(".") ? Double.parseDouble(text) : Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw error("Invalid number: " + text);
        }
    }

    private String readIdentifier() {
        skipWhitespace();
        if (isAtEnd() || !isIdentStart(peek())) {
            throw error("Expected identifier");
        }

        int start = pos;
        while (!isAtEnd() && isIdentPart(peek())) pos++;
        return input.substring(start, pos);
    }

    private List<Object> parseList() {
        skipWhitespace();
        boolean bracket = matchChar('[');
        if (!bracket) expectChar('(');

        List<Object> values = new ArrayList<>();
        skipWhitespace();

        char closingChar = bracket ? ']' : ')';
        if (!isAtEnd() && peek() != closingChar) {
            values.add(readValue());
            while (matchChar(',')) {
                values.add(readValue());
            }
        }

        expectChar(closingChar);
        return values;
    }

    // ========== Scanning Helpers ==========

    private boolean matchKeyword(String keyword) {
        skipWhitespace();
        int end = pos + keyword.length();
        if (end > input.length()) return false;

        // Check chars match (case-insensitive)
        if (!input.substring(pos, end).equalsIgnoreCase(keyword)) return false;

        // Ensure it's a whole word (not a prefix of a longer identifier)
        if (end < input.length() && isIdentPart(input.charAt(end))) return false;

        pos = end;
        return true;
    }

    private boolean matchOp(String op) {
        skipWhitespace();
        if (pos + op.length() > input.length()) return false;
        if (!input.startsWith(op, pos)) return false;
        pos += op.length();
        return true;
    }

    private boolean matchChar(char c) {
        skipWhitespace();
        if (isAtEnd() || peek() != c) return false;
        pos++;
        return true;
    }

    private void expectChar(char c) {
        if (!matchChar(c)) throw error("Expected '" + c + "'");
    }

    private void skipWhitespace() {
        while (!isAtEnd() && Character.isWhitespace(peek())) pos++;
    }

    private char peek() {
        return input.charAt(pos);
    }

    private char advance() {
        return input.charAt(pos++);
    }

    private boolean isAtEnd() {
        return pos >= input.length();
    }

    private boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$';
    }

    private String normalizeField(String field) {
        for (VariableSource source : VariableSource.values()) {
            if (field.startsWith(source.getPrefix() + ".")) {
                return field;
            }
        }
        return VariableSource.REQUEST.getPrefix() + "." + field;
    }

    private ConfigurationException error(String message) {
        return new ConfigurationException("Expression error at position "
                + pos + ": " + message + " in '" + input + "'");
    }
}
