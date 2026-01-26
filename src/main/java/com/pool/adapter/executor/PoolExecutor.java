package com.pool.adapter.executor;

import com.pool.core.TaskContext;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for submitting tasks to Pool.
 * Provides intelligent priority-based task execution.
 */
public interface PoolExecutor {

    /**
     * Submit a runnable task with context for priority calculation.
     *
     * @param context Task context containing variables for priority calculation
     * @param task    The runnable task to execute
     * @throws com.pool.exception.TaskRejectedException if queue is full or executor is shutdown
     */
    void submit(TaskContext context, Runnable task);

    /**
     * Submit a callable task with context for priority calculation.
     *
     * @param context Task context containing variables for priority calculation
     * @param task    The callable task to execute
     * @param <T>     Return type of the task
     * @return Future representing the pending result
     * @throws com.pool.exception.TaskRejectedException if queue is full or executor is shutdown
     */
    <T> Future<T> submit(TaskContext context, Callable<T> task);

    /**
     * Graceful shutdown - waits for queued tasks to complete.
     */
    void shutdown();

    /**
     * Immediate shutdown - attempts to stop all tasks.
     */
    void shutdownNow();

    /**
     * Wait for termination after shutdown.
     *
     * @param timeout Maximum time to wait
     * @param unit    Time unit
     * @return true if terminated, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Check if executor has been shutdown.
     */
    boolean isShutdown();

    /**
     * Check if executor has terminated (all tasks completed after shutdown).
     */
    boolean isTerminated();

    /**
     * Get current queue depth.
     */
    int getQueueSize();

    /**
     * Get number of currently active threads.
     */
    int getActiveCount();
}
