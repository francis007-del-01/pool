package com.pool.config;

/**
 * Configuration for a named queue.
 *
 * @param name     Queue name (used for routing)
 * @param index    Priority index (lower = higher priority when fetching)
 * @param capacity Maximum queue capacity
 */
public record QueueConfig(
        String name,
        int index,
        int capacity
) {
    public static QueueConfig defaults(String name, int index) {
        return new QueueConfig(name, index, 1000);
    }
}
