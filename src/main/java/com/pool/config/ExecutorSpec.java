package com.pool.config;

/**
 * Configuration for an executor (worker pool) that targets a named queue.
 *
 * @param queueName              Target queue name (must exist in scheduler.queues)
 * @param corePoolSize           Core worker threads
 * @param maxPoolSize            Maximum worker threads
 * @param keepAliveSeconds       Idle timeout before excess threads terminate
 * @param threadNamePrefix       Thread name prefix
 * @param allowCoreThreadTimeout Whether core threads can timeout
 */
public record ExecutorSpec(
        String queueName,
        int corePoolSize,
        int maxPoolSize,
        int keepAliveSeconds,
        String threadNamePrefix,
        boolean allowCoreThreadTimeout
) {
    public static ExecutorSpec defaults(String queueName) {
        return new ExecutorSpec(
                queueName,
                10,
                50,
                60,
                queueName + "-worker-",
                true
        );
    }
}
