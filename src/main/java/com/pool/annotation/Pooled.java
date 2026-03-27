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
 *   <li>A method argument matching {@link #contextType()} — parsed to a map
 *       by the existing request parser</li>
 *   <li>Implicit variables: {@code _class} and {@code _method}</li>
 * </ul>
 *
 * <p>Class-level annotation gates all public methods. Method-level overrides class-level.
 *
 * <p>Example:
 * <pre>
 * &#64;Service
 * &#64;Pooled(contextType = Headers.class)
 * public class MyService {
 *     public void doWork(String id, Headers headers) { ... }
 * }
 * </pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pooled {

    /**
     * Type of method argument to auto-detect and parse as request context.
     * The aspect scans method arguments for the first {@code instanceof} match.
     * Defaults to {@code Void.class} meaning MDC-only context (no request arg parsing).
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
