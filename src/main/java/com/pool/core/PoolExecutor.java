package com.pool.core;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Main entry point for submitting tasks to Pool.
 * Provides intelligent priority-based task execution.
 */
public interface PoolExecutor {

    /**
     * Submit a callable task with context for priority calculation.
     *
     * @param task    The callable task to execute
     * @param context Task context containing variables for priority calculation
     * @param <T>     Return type of the task
     * @return Future representing the pending result
     * @throws com.pool.exception.TaskRejectedException if queue is full or executor is shutdown
     */
    <T> Future<T> submit(Callable<T> task, TaskContext context);

    /**
     * Submit a runnable task with context.
     *
     * @param task    The runnable task to execute
     * @param context Task context containing variables for priority calculation
     * @throws com.pool.exception.TaskRejectedException if queue is full or executor is shutdown
     */
    void execute(Runnable task, TaskContext context);

    /**
     * Graceful shutdown - waits for queued tasks to complete.
     */
    void shutdown();

    /**
     * Immediate shutdown - attempts to stop all tasks.
     */
    void shutdownNow();

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

    /**
     * Reload configuration (called on config change or restart).
     */
    void reloadConfig();
}
