package com.pool.condition.impl;

import com.pool.condition.Condition;
import com.pool.condition.ConditionType;
import com.pool.core.TaskContext;
import com.pool.variable.VariableResolver;

import java.util.Collection;
import java.util.Optional;

/**
 * Condition that checks if a collection field contains a specific value,
 * or if a string field contains a substring.
 */
public class ContainsCondition implements Condition {

    private final String field;
    private final Object searchValue;
    private final VariableResolver resolver;

    public ContainsCondition(String field, Object searchValue, VariableResolver resolver) {
        this.field = field;
        this.searchValue = searchValue;
        this.resolver = resolver;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean evaluate(TaskContext context) {
        Optional<Object> actual = resolver.resolve(field, context);
        if (actual.isEmpty()) {
            return false; // Missing variable = condition is false
        }

        Object value = actual.get();
        
        // String contains
        if (value instanceof String str) {
            return str.contains(String.valueOf(searchValue));
        }
        
        // Collection contains
        if (value instanceof Collection<?> collection) {
            return collection.contains(searchValue);
        }
        
        // Array contains
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            for (Object element : array) {
                if (searchValue.equals(element)) {
                    return true;
                }
            }
            return false;
        }
        
        return false;
    }

    @Override
    public ConditionType getType() {
        return ConditionType.CONTAINS;
    }

    @Override
    public String toString() {
        return field + " CONTAINS " + searchValue;
    }
}
