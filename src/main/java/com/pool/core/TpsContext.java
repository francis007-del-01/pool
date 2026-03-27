package com.pool.core;

/**
 * Thread-local context for TPS admission bypass.
 *
 * <p>When a request is admitted through the TPS gate, this context is marked
 * as "processed". Any downstream calls to other {@code @Pooled}-annotated
 * services on the same thread (or child threads) will skip the TPS gate
 * entirely — implementing the "once admitted, always pass" semantic.
 *
 * <p>Uses {@link InheritableThreadLocal} so child threads spawned by an
 * admitted request automatically inherit the "processed" state.
 */
public final class TpsContext {

    private static final InheritableThreadLocal<Boolean> PROCESSED =
            new InheritableThreadLocal<>() {
                @Override
                protected Boolean initialValue() {
                    return false;
                }
            };

    private TpsContext() {}

    /**
     * Check if the current thread has already been admitted through the TPS gate.
     */
    public static boolean isProcessed() {
        return PROCESSED.get();
    }

    /**
     * Mark the current thread as admitted. Child threads will inherit this state.
     */
    public static void markProcessed() {
        PROCESSED.set(true);
    }

    /**
     * Clear the admission state. Called in the finally block of the entry-point aspect.
     */
    public static void clear() {
        PROCESSED.remove();
    }
}
