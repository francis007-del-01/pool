package com.pool.config.expression;

import com.pool.exception.ConfigurationException;

import java.util.ArrayList;
import java.util.List;

import static com.pool.config.expression.ExpressionConfig.*;

/**
 * Tokenizer for condition expressions.
 * Converts input string into a sequence of tokens.
 */
public final class ExpressionTokenizer {

    private final String input;
    private final int length;
    private int pos;

    public ExpressionTokenizer(String input) {
        this.input = input;
        this.length = input.length();
        this.pos = 0;
    }

    /**
     * Tokenize the input string.
     *
     * @return List of tokens
     */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        
        while (!isAtEnd()) {
            char c = peek();
            
            // Skip whitespace
            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }
            
            int start = pos;
            
            switch (c) {
                case Operators.LEFT_PAREN -> {
                    advance();
                    tokens.add(new Token(TokenType.LPAREN, "(", null, start));
                }
                case Operators.RIGHT_PAREN -> {
                    advance();
                    tokens.add(new Token(TokenType.RPAREN, ")", null, start));
                }
                case Operators.LEFT_BRACKET -> {
                    advance();
                    tokens.add(new Token(TokenType.LBRACKET, "[", null, start));
                }
                case Operators.RIGHT_BRACKET -> {
                    advance();
                    tokens.add(new Token(TokenType.RBRACKET, "]", null, start));
                }
                case Operators.COMMA -> {
                    advance();
                    tokens.add(new Token(TokenType.COMMA, ",", null, start));
                }
                case Operators.EQUALS -> {
                    advance();
                    if (match(Operators.EQUALS)) {
                        tokens.add(new Token(TokenType.EQ, "==", null, start));
                    } else {
                        tokens.add(new Token(TokenType.EQ, "=", null, start));
                    }
                }
                case Operators.NOT_EQUALS -> {
                    advance();
                    if (match(Operators.EQUALS)) {
                        tokens.add(new Token(TokenType.NE, "!=", null, start));
                    } else {
                        throw error("Unexpected '!'", start);
                    }
                }
                case Operators.GREATER -> {
                    advance();
                    if (match(Operators.EQUALS)) {
                        tokens.add(new Token(TokenType.GTE, ">=", null, start));
                    } else {
                        tokens.add(new Token(TokenType.GT, ">", null, start));
                    }
                }
                case Operators.LESS -> {
                    advance();
                    if (match(Operators.EQUALS)) {
                        tokens.add(new Token(TokenType.LTE, "<=", null, start));
                    } else {
                        tokens.add(new Token(TokenType.LT, "<", null, start));
                    }
                }
                case Operators.QUOTE_DOUBLE, Operators.QUOTE_SINGLE -> tokens.add(readString());
                default -> {
                    if (isIdentifierStart(c)) {
                        tokens.add(readIdentifierOrKeyword());
                    } else if (isNumberStart(c)) {
                        tokens.add(readNumber());
                    } else {
                        throw error("Unexpected character '" + c + "'", start);
                    }
                }
            }
        }
        
        tokens.add(new Token(TokenType.EOF, "", null, pos));
        return tokens;
    }

    private Token readIdentifierOrKeyword() {
        int start = pos;
        
        while (!isAtEnd() && isIdentifierPart(peek())) {
            advance();
        }
        
        String text = input.substring(start, pos);
        String upper = text.toUpperCase();
        
        // Check if it's a keyword
        TokenType keywordType = KEYWORDS.get(upper);
        if (keywordType != null) {
            Object literal = null;
            if (keywordType == TokenType.BOOLEAN) {
                literal = BOOLEAN_VALUES.get(upper);
            }
            return new Token(keywordType, text, literal, start);
        }
        
        // Regular identifier
        return new Token(TokenType.IDENT, text, text, start);
    }

    private Token readNumber() {
        int start = pos;
        
        if (peek() == Operators.MINUS) {
            advance();
        }
        
        while (!isAtEnd() && Character.isDigit(peek())) {
            advance();
        }
        
        if (!isAtEnd() && peek() == Operators.DOT) {
            advance();
            while (!isAtEnd() && Character.isDigit(peek())) {
                advance();
            }
        }
        
        String text = input.substring(start, pos);
        Object number;
        
        try {
            if (text.contains(".")) {
                number = Double.parseDouble(text);
            } else {
                number = Long.parseLong(text);
            }
        } catch (NumberFormatException e) {
            throw error("Invalid number '" + text + "'", start);
        }
        
        return new Token(TokenType.NUMBER, text, number, start);
    }

    private Token readString() {
        int start = pos;
        char quote = advance();
        StringBuilder sb = new StringBuilder();
        
        while (!isAtEnd() && peek() != quote) {
            char c = advance();
            
            if (c == Operators.BACKSLASH && !isAtEnd()) {
                char escaped = advance();
                switch (escaped) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\', '"', '\'' -> sb.append(escaped);
                    default -> sb.append(escaped);
                }
            } else {
                sb.append(c);
            }
        }
        
        if (isAtEnd()) {
            throw error("Unterminated string", start);
        }
        
        advance(); // closing quote
        return new Token(TokenType.STRING, sb.toString(), sb.toString(), start);
    }

    private boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == Operators.UNDERSCORE || c == Operators.DOLLAR;
    }

    private boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) 
                || c == Operators.UNDERSCORE 
                || c == Operators.DOT 
                || c == Operators.DOLLAR;
    }

    private boolean isNumberStart(char c) {
        return Character.isDigit(c) || c == Operators.MINUS;
    }

    private char advance() {
        return input.charAt(pos++);
    }

    private boolean match(char expected) {
        if (isAtEnd() || input.charAt(pos) != expected) {
            return false;
        }
        pos++;
        return true;
    }

    private char peek() {
        return input.charAt(pos);
    }

    private boolean isAtEnd() {
        return pos >= length;
    }

    private ConfigurationException error(String message, int position) {
        return new ConfigurationException("Invalid condition-expr at position "
                + position + ": " + message + " in '" + input + "'");
    }
}
