package com.pool.exception;

/**
 * Thrown when a request cannot be admitted within the configured timeout
 * because the executor's TPS budget is exhausted.
 */
public class TpsExceededException extends PoolException {

    private final String executorId;
    private final long timeoutMs;

    public TpsExceededException(String executorId, long timeoutMs) {
        super("TPS exceeded for executor '" + executorId + "' after waiting " + timeoutMs + "ms");
        this.executorId = executorId;
        this.timeoutMs = timeoutMs;
    }

    public TpsExceededException(String message) {
        super(message);
        this.executorId = null;
        this.timeoutMs = 0;
    }

    public String getExecutorId() {
        return executorId;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
