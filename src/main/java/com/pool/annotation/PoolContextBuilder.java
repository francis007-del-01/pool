package com.pool.annotation;

/**
 * Contract for building a priority context object from method arguments.
 *
 * <p>Implement this interface as a Spring bean and reference it via
 * {@link Pooled#contextType()}. The aspect will retrieve the bean from
 * the Spring context, call {@link #build(Object[])} with the intercepted
 * method's arguments, and serialize the returned object as {@code $req.*}
 * variables for policy evaluation.
 *
 * <p>Example:
 * <pre>
 * &#64;Component
 * public class OrderContextBuilder implements PoolContextBuilder {
 *     &#64;Override
 *     public Object build(Object[] args) {
 *         OrderRequest req = (OrderRequest) args[0];
 *         CustomerInfo customer = (CustomerInfo) args[1];
 *         return Map.of(
 *             "amount", req.getAmount(),
 *             "tier",   customer.getTier()
 *         );
 *     }
 * }
 * </pre>
 */
@FunctionalInterface
public interface PoolContextBuilder {

    /**
     * Build the context object from the intercepted method's arguments.
     *
     * @param args the method arguments in declaration order
     * @return the object to serialize as {@code $req.*} variables; must be
     *         serializable by Jackson. Return {@code null} to produce no request vars.
     */
    Object build(Object[] args);
}
