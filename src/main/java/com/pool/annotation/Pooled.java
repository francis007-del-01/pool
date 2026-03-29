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
 *   <li>A {@link PoolContextBuilder} Spring bean declared via {@link #contextType()} —
 *       receives all method arguments and returns the object serialized as
 *       {@code $req.*} variables</li>
 *   <li>Implicit variables: {@code _class} and {@code _method}</li>
 * </ul>
 *
 * <p>Class-level annotation gates all public methods. Method-level overrides class-level.
 *
 * <p>Examples:
 * <pre>
 * // With context builder
 * &#64;Component
 * public class OrderContextBuilder implements PoolContextBuilder {
 *     public Object build(Object[] args) {
 *         OrderRequest req = (OrderRequest) args[0];
 *         return Map.of("amount", req.getAmount(), "tier", req.getTier());
 *     }
 * }
 *
 * &#64;Service
 * &#64;Pooled(contextType = OrderContextBuilder.class)
 * public class OrderService {
 *     public void process(OrderRequest req) { ... }
 * }
 *
 * // MDC-only (no request args)
 * &#64;Service
 * &#64;Pooled
 * public class HealthService {
 *     public void check() { ... }
 * }
 * </pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pooled {

    /**
     * A {@link PoolContextBuilder} implementation class (must be a Spring bean).
     * The builder receives all method arguments and returns the object to serialize
     * as {@code $req.*} variables for policy evaluation.
     * Defaults to {@code Void.class} meaning MDC-only context (no request args parsed).
     */
    Class<?> contextType() default Void.class;

    /**
     * Timeout in milliseconds for TPS admission.
     * If TPS is exceeded, the request is queued and the caller blocks until
     * admitted or the timeout expires (throwing {@code TpsExceededException}).
     * A value of {@code -1} means use the pool's default timeout.
     */
    long timeoutMs() default -1;
}
