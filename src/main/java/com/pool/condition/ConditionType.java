package com.pool.condition;

/**
 * Supported condition types for priority tree evaluation.
 */
public enum ConditionType {
    // Comparison
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    BETWEEN,

    // Collection
    IN,
    NOT_IN,
    CONTAINS,

    // String
    REGEX,
    STARTS_WITH,
    ENDS_WITH,

    // Existence
    EXISTS,
    IS_NULL,

    // Logical
    AND,
    OR,
    NOT,

    // Special
    ALWAYS_TRUE
}
