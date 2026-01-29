package com.pool.config.expression;

import java.util.Map;

/**
 * Configuration for expression parsing keywords and operators.
 */
public final class ExpressionConfig {

    private ExpressionConfig() {
    }

    /**
     * Keywords mapped to token types.
     */
    public static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            // Logical
            Map.entry("AND", TokenType.AND),
            Map.entry("OR", TokenType.OR),
            Map.entry("NOT", TokenType.NOT),
            
            // Collection
            Map.entry("IN", TokenType.IN),
            Map.entry("CONTAINS", TokenType.CONTAINS),
            
            // String
            Map.entry("REGEX", TokenType.REGEX),
            Map.entry("STARTS_WITH", TokenType.STARTS_WITH),
            Map.entry("ENDS_WITH", TokenType.ENDS_WITH),
            
            // Existence
            Map.entry("EXISTS", TokenType.EXISTS),
            Map.entry("IS_NULL", TokenType.IS_NULL),
            
            // Literals
            Map.entry("TRUE", TokenType.BOOLEAN),
            Map.entry("FALSE", TokenType.BOOLEAN),
            Map.entry("NULL", TokenType.NULL)
    );

    /**
     * Boolean literal values.
     */
    public static final Map<String, Boolean> BOOLEAN_VALUES = Map.of(
            "TRUE", true,
            "FALSE", false
    );

    /**
     * Operator symbols.
     */
    public static final class Operators {
        public static final char LEFT_PAREN = '(';
        public static final char RIGHT_PAREN = ')';
        public static final char LEFT_BRACKET = '[';
        public static final char RIGHT_BRACKET = ']';
        public static final char COMMA = ',';
        public static final char EQUALS = '=';
        public static final char NOT_EQUALS = '!';
        public static final char GREATER = '>';
        public static final char LESS = '<';
        public static final char QUOTE_DOUBLE = '"';
        public static final char QUOTE_SINGLE = '\'';
        public static final char BACKSLASH = '\\';
        public static final char DOT = '.';
        public static final char MINUS = '-';
        public static final char UNDERSCORE = '_';
        public static final char DOLLAR = '$';
        
        private Operators() {
        }
    }

    /**
     * Field name prefix for request variables.
     * Fields without a prefix are automatically prefixed with this.
     */
    public static final String REQUEST_PREFIX = "$req.";

    /**
     * Field name prefix for system variables.
     */
    public static final String SYSTEM_PREFIX = "$sys.";
}
