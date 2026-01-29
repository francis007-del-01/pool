package com.pool.config;

/**
 * Configuration for an executor (worker pool) that targets a named queue.
 *
 * @param queueName              Target queue name (must exist in scheduler.queues)
 * @param workerCount            Fixed worker threads for this queue
 * @param keepAliveSeconds       Idle timeout before workers can terminate
 * @param allowCoreThreadTimeout Whether workers can timeout when idle
 */
public record ExecutorSpec(
        String queueName,
        int workerCount,
        int keepAliveSeconds,
        boolean allowCoreThreadTimeout
) {
    public static ExecutorSpec defaults(String queueName) {
        return new ExecutorSpec(queueName, 10, 60, true);
    }

    /**
     * Default thread name prefix derived from the queue name.
     */
    public String threadNamePrefix() {
        return queueName + "-worker-";
    }
}
