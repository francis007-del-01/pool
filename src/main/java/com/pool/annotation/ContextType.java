package com.pool.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method argument as a named request context for {@link Pooled}.
 *
 * <p>Each entry is matched positionally to the first unconsumed method argument
 * that is {@code instanceof type()}. If multiple entries share the same type,
 * provide explicit {@link #name()} values to distinguish them.
 *
 * <p>The matched argument is serialized and its fields are exposed as
 * {@code $req.<name>.<field>} variables in YAML conditions and sort-by expressions.
 *
 * <p>If {@link #name()} is omitted, the class simple name is used with its
 * first letter lowercased (e.g. {@code OrderRequest} → {@code orderRequest}).
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface ContextType {

    /**
     * Namespace for this argument's fields in expressions (e.g. {@code "order"} → {@code $req.order.field}).
     * Defaults to the class simple name with first letter lowercased.
     */
    String name() default "";

    /**
     * Type of the method argument to match.
     */
    Class<?> type();
}
