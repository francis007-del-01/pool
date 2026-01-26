package com.pool.scheduler;

import com.pool.core.TaskContext;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Priority scheduler abstraction for external consumers.
 * Evaluates policy and orders payloads without executing tasks.
 *
 * @param <T> Payload type
 */
public interface PriorityScheduler<T> {

    /**
     * Submit a payload with context for priority evaluation.
     *
     * @return true if accepted, false if rejected (e.g., queue full or shutdown)
     */
    boolean submit(TaskContext context, T payload);

    /**
     * Get next payload to process (blocking).
     */
    T getNext() throws InterruptedException;

    /**
     * Get next payload to process with timeout.
     */
    Optional<T> getNext(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Current queue size.
     */
    int size();

    /**
     * Remaining capacity.
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
