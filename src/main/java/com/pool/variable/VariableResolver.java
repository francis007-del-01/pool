package com.pool.variable;

import com.pool.core.TaskContext;

import java.util.Optional;

/**
 * Resolves variable references from task context.
 */
public interface VariableResolver {

    /**
     * Resolve a variable reference.
     *
     * @param reference Variable reference (e.g., "$req.amount", "$sys.submittedAt", "$ctx.clientId")
     * @param context   Task context containing variable values
     * @return Resolved value, or empty if not found
     */
    Optional<Object> resolve(String reference, TaskContext context);

    /**
     * Resolve and cast to specific type.
     *
     * @param reference Variable reference
     * @param context   Task context
     * @param type      Expected type
     * @param <T>       Type parameter
     * @return Resolved and cast value, or empty if not found or wrong type
     */
    <T> Optional<T> resolve(String reference, TaskContext context, Class<T> type);

    /**
     * Resolve a variable as a long value (for priority calculations).
     * Handles conversion from various numeric types.
     *
     * @param reference Variable reference
     * @param context   Task context
     * @return Long value, or empty if not found or not numeric
     */
    Optional<Long> resolveAsLong(String reference, TaskContext context);

    /**
     * Resolve a variable as a double value (for comparisons).
     *
     * @param reference Variable reference
     * @param context   Task context
     * @return Double value, or empty if not found or not numeric
     */
    Optional<Double> resolveAsDouble(String reference, TaskContext context);

    /**
     * Resolve a variable as a String value.
     *
     * @param reference Variable reference
     * @param context   Task context
     * @return String value, or null if not found
     */
    default String resolveAsString(String reference, TaskContext context) {
        return resolve(reference, context)
                .map(Object::toString)
                .orElse(null);
    }
}
