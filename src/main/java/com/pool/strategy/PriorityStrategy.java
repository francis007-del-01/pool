package com.pool.strategy;

import com.pool.core.PrioritizedTask;

import java.util.Optional;

/**
 * Strategy interface for task selection at execution time.
 * <p>
 * While priority calculation happens once at submission (static),
 * the actual task selection is handled by the PriorityStrategy.
 * <p>
 * This allows different execution-time behaviors:
 * - FIFO: Simple priority queue, oldest task with highest priority wins
 * - TIME_BASED (future): Boost priority based on wait time (aging)
 * - BUCKET_BASED (future): Multi-level buckets with promotion
 */
public interface PriorityStrategy {

    /**
     * Get the strategy type name.
     */
    String getName();

    /**
     * Enqueue a task into the strategy's queue structure.
     *
     * @param task The prioritized task to enqueue
     * @return true if successfully enqueued, false if rejected (e.g., queue full)
     */
    boolean enqueue(PrioritizedTask<?> task);

    /**
     * Select and remove the next task to execute (blocking).
     * This is called by worker threads.
     *
     * @return The next task to execute
     * @throws InterruptedException if interrupted while waiting
     */
    PrioritizedTask<?> takeNext() throws InterruptedException;

    /**
     * Select and remove the next task to execute (non-blocking).
     *
     * @return The next task, or empty if queue is empty
     */
    Optional<PrioritizedTask<?>> pollNext();

    /**
     * Get the current queue depth.
     */
    int getQueueSize();

    /**
     * Check if the queue is empty.
     */
    boolean isEmpty();

    /**
     * Get the queue capacity (max tasks that can be queued).
     */
    int getCapacity();

    /**
     * Get remaining capacity.
     */
    int getRemainingCapacity();

    /**
     * Shutdown the strategy (cleanup any daemon threads, etc.)
     */
    void shutdown();
}
