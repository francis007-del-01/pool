package com.pool.scheduler;

import com.pool.core.TaskContext;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Priority scheduler abstraction for external consumers.
 * Evaluates policy and orders payloads without executing tasks.
 * Supports multiple named queues.
 *
 * @param <T> Payload type
 */
public interface PriorityScheduler<T> {

    /**
     * Submit a payload with context for priority evaluation.
     * Returns the target queue name based on policy evaluation.
     *
     * @return Queue name if accepted, null if rejected (e.g., queue full or shutdown)
     */
    String submit(TaskContext context, T payload);

    /**
     * Get next payload from first non-empty queue (blocking).
     * Queues are checked in index order (lowest index first).
     */
    T getNext() throws InterruptedException;

    /**
     * Get next payload from first non-empty queue with timeout.
     * Queues are checked in index order (lowest index first).
     */
    Optional<T> getNext(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Get next payload from a specific queue (blocking).
     *
     * @param queueName Target queue name
     */
    T getNext(String queueName) throws InterruptedException;

    /**
     * Get next payload from a specific queue with timeout.
     *
     * @param queueName Target queue name
     */
    Optional<T> getNext(String queueName, long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Total size across all queues.
     */
    int size();

    /**
     * Size of a specific queue.
     */
    int size(String queueName);

    /**
     * Total remaining capacity across all queues.
     */
    int remainingCapacity();

    /**
     * Shutdown the scheduler (reject new submissions).
     */
    void shutdown();

    /**
     * Whether shutdown has been requested.
     */
    boolean isShutdown();
}
