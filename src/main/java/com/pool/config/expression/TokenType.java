package com.pool.config.expression;

/**
 * Token types for expression parsing.
 */
public enum TokenType {
    // Identifiers and literals
    IDENT,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    
    // Delimiters
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    COMMA,
    
    // Logical operators
    AND,
    OR,
    NOT,
    
    // Comparison operators
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
    
    // Collection operators
    IN,
    CONTAINS,
    
    // String operators
    REGEX,
    STARTS_WITH,
    ENDS_WITH,
    
    // Existence operators
    EXISTS,
    IS_NULL,
    
    // Special
    EOF
}
