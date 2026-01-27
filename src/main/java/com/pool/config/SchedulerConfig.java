package com.pool.config;

import java.util.List;

/**
 * Configuration for the priority scheduler.
 *
 * @param queues List of queue configurations
 */
public record SchedulerConfig(
        List<QueueConfig> queues
) {
    /**
     * Default scheduler config with a single default queue.
     */
    public static SchedulerConfig defaults() {
        return new SchedulerConfig(List.of(QueueConfig.defaults("default", 0)));
    }

    /**
     * Minimal config for testing.
     */
    public static SchedulerConfig minimal() {
        return defaults();
    }

    /**
     * Get total capacity across all queues.
     */
    public int totalCapacity() {
        return queues.stream().mapToInt(QueueConfig::capacity).sum();
    }

    /**
     * Find queue by name.
     */
    public QueueConfig getQueue(String name) {
        return queues.stream()
                .filter(q -> q.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
