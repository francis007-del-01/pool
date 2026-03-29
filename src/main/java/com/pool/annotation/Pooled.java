package com.pool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or method for TPS-gated execution.
 *
 * <p>The annotation itself does not specify which executor to use — that is
 * determined by the PolicyEngine based on the YAML priority-tree configuration.
 *
 * <p>Context for policy evaluation is built from:
 * <ul>
 *   <li>MDC (SLF4J Mapped Diagnostic Context) — ambient request context</li>
 *   <li>Method arguments declared via {@link #contextTypes()} — each serialized
 *       under its own namespace as {@code $req.<name>.<field>}</li>
 *   <li>Implicit variables: {@code _class} and {@code _method}</li>
 * </ul>
 *
 * <p>Class-level annotation gates all public methods. Method-level overrides class-level.
 *
 * <p>Examples:
 * <pre>
 * // Single context type — auto-named from class ($req.orderRequest.*)
 * &#64;Pooled(contextTypes = &#64;ContextType(type = OrderRequest.class))
 * public void process(OrderRequest order) { ... }
 *
 * // Two different types — auto-named
 * &#64;Pooled(contextTypes = {
 *     &#64;ContextType(type = OrderRequest.class),   // $req.orderRequest.*
 *     &#64;ContextType(type = CustomerInfo.class)    // $req.customerInfo.*
 * })
 * public void process(OrderRequest order, CustomerInfo customer) { ... }
 *
 * // Same type twice — explicit names required
 * &#64;Pooled(contextTypes = {
 *     &#64;ContextType(name = "before", type = OrderRequest.class),
 *     &#64;ContextType(name = "after",  type = OrderRequest.class)
 * })
 * public void compare(OrderRequest before, OrderRequest after) { ... }
 * </pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pooled {

    /**
     * Declares which method arguments to include as named request context.
     * Each entry is matched positionally to the first unconsumed argument of
     * the declared type. Fields are exposed as {@code $req.<name>.<field>}.
     * If empty, no request variables are extracted (MDC-only context).
     */
    ContextType[] contextTypes() default {};

    /**
     * Timeout in milliseconds for TPS admission.
     * If TPS is exceeded, the request is queued and the caller blocks until
     * admitted or the timeout expires (throwing {@code TpsExceededException}).
     * A value of {@code -1} means use the pool's default timeout.
     */
    long timeoutMs() default -1;
}
