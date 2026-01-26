package com.pool.config;

/**
 * Priority scheduler configuration.
 *
 * @param queueCapacity Maximum queue capacity
 */
public record SchedulerConfig(int queueCapacity) {
    /**
     * Default scheduler configuration.
     */
    public static SchedulerConfig defaults() {
        return new SchedulerConfig(1000);
    }

    /**
     * Create a minimal configuration for testing.
     */
    public static SchedulerConfig minimal() {
        return new SchedulerConfig(100);
    }
}
