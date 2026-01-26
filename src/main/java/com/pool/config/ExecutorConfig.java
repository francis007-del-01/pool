package com.pool.config;

/**
 * Thread pool executor configuration.
 *
 * @param corePoolSize          Minimum number of threads
 * @param maxPoolSize           Maximum number of threads
 * @param queueCapacity         Maximum queue capacity
 * @param keepAliveSeconds      Time to keep idle threads alive
 * @param threadNamePrefix      Prefix for thread names
 * @param allowCoreThreadTimeout Whether core threads can timeout
 */
public record ExecutorConfig(
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity,
        int keepAliveSeconds,
        String threadNamePrefix,
        boolean allowCoreThreadTimeout
) {
    /**
     * Default executor configuration.
     */
    public static ExecutorConfig defaults() {
        return new ExecutorConfig(
                50,          // corePoolSize
                200,         // maxPoolSize
                10000,       // queueCapacity
                60,          // keepAliveSeconds
                "pool-",     // threadNamePrefix
                true         // allowCoreThreadTimeout
        );
    }

    /**
     * Create a minimal configuration for testing.
     */
    public static ExecutorConfig minimal() {
        return new ExecutorConfig(
                2,           // corePoolSize
                10,          // maxPoolSize
                100,         // queueCapacity
                60,          // keepAliveSeconds
                "pool-",     // threadNamePrefix
                true         // allowCoreThreadTimeout
        );
    }
}
