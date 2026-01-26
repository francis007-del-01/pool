package com.pool.config;

import com.pool.condition.ConditionType;

import java.util.List;

/**
 * Configuration for a condition.
 *
 * @param type       Condition type (EQUALS, GREATER_THAN, AND, etc.)
 * @param field      Variable reference for comparison (e.g., "$req.amount")
 * @param value      Expected value for comparison conditions
 * @param value2     Second value for BETWEEN condition
 * @param values     List of values for IN/NOT_IN conditions
 * @param pattern    Pattern for REGEX/STARTS_WITH/ENDS_WITH conditions
 * @param conditions Nested conditions for AND/OR/NOT logical conditions
 */
public record ConditionConfig(
        ConditionType type,
        String field,
        Object value,
        Object value2,
        List<Object> values,
        String pattern,
        List<ConditionConfig> conditions
) {
    /**
     * Create an ALWAYS_TRUE condition.
     */
    public static ConditionConfig alwaysTrue() {
        return new ConditionConfig(ConditionType.ALWAYS_TRUE, null, null, null, null, null, null);
    }

    /**
     * Create an EQUALS condition.
     */
    public static ConditionConfig equals(String field, Object value) {
        return new ConditionConfig(ConditionType.EQUALS, field, value, null, null, null, null);
    }

    /**
     * Create a GREATER_THAN condition.
     */
    public static ConditionConfig greaterThan(String field, Object value) {
        return new ConditionConfig(ConditionType.GREATER_THAN, field, value, null, null, null, null);
    }

    /**
     * Create an AND condition.
     */
    public static ConditionConfig and(List<ConditionConfig> conditions) {
        return new ConditionConfig(ConditionType.AND, null, null, null, null, null, conditions);
    }

    /**
     * Create an OR condition.
     */
    public static ConditionConfig or(List<ConditionConfig> conditions) {
        return new ConditionConfig(ConditionType.OR, null, null, null, null, null, conditions);
    }

    /**
     * Create an IN condition.
     */
    public static ConditionConfig in(String field, List<Object> values) {
        return new ConditionConfig(ConditionType.IN, field, null, null, values, null, null);
    }
}
