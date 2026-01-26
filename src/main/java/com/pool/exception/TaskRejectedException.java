package com.pool.exception;

/**
 * Exception thrown when a task cannot be accepted by the pool.
 * Typically due to queue being full or executor being shutdown.
 */
public class TaskRejectedException extends PoolException {

    public TaskRejectedException(String message) {
        super(message);
    }

    public TaskRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
