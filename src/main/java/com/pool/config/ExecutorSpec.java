package com.pool.config;

/**
 * Configuration for an executor associated with a queue.
 *
 * @param queue               Queue configuration
 * @param corePoolSize        Core worker threads
 * @param maxPoolSize         Maximum worker threads
 * @param keepAliveSeconds    Idle timeout before excess threads terminate
 * @param threadNamePrefix    Thread name prefix
 * @param allowCoreThreadTimeout Whether core threads can timeout
 */
public record ExecutorSpec(
        QueueConfig queue,
        int corePoolSize,
        int maxPoolSize,
        int keepAliveSeconds,
        String threadNamePrefix,
        boolean allowCoreThreadTimeout
) {
    public static ExecutorSpec defaults(String queueName, int index) {
        return new ExecutorSpec(
                QueueConfig.defaults(queueName, index),
                10,
                50,
                60,
                queueName + "-worker-",
                true
        );
    }
}
