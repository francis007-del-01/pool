package com.pool.adapter.executor;

import java.util.concurrent.FutureTask;

/**
 * Runnable wrapper around a FutureTask with a stable taskId for logging.
 */
final class ExecutableTask implements Runnable {
    private final FutureTask<?> futureTask;
    private final String taskId;

    ExecutableTask(FutureTask<?> futureTask, String taskId) {
        this.futureTask = futureTask;
        this.taskId = taskId;
    }

    @Override
    public void run() {
        futureTask.run();
    }

    String getTaskId() {
        return taskId;
    }
}
