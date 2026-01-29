package com.pool.config.expression;

/**
 * Represents a token in an expression.
 *
 * @param type     Token type
 * @param text     Original text
 * @param literal  Parsed literal value (for strings, numbers, booleans)
 * @param position Position in the input string
 */
public record Token(TokenType type, String text, Object literal, int position) {
    
    @Override
    public String toString() {
        if (literal != null) {
            return type + "(" + literal + ")";
        }
        return type + "(" + text + ")";
    }
}
