package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Condition that checks if a field value matches a regular expression.
 */
public class RegexCondition implements Condition {

    private final String field;
    private final Pattern pattern;
    private final VariableResolver resolver;

    public RegexCondition(String field, String regex, VariableResolver resolver) {
        this.field = field;
        this.pattern = Pattern.compile(regex);
        this.resolver = resolver;
    }

    @Override
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }

        String value = String.valueOf(actual.get());
        return pattern.matcher(value).matches();
    }

    @Override
    public ConditionType getType() {
        return ConditionType.REGEX;
    }

    @Override
    public String toString() {
        return field + " MATCHES /" + pattern.pattern() + "/";
    }
}
