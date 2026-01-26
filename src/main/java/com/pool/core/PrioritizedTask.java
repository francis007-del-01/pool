package com.pool.core;

import com.pool.policy.EvaluationResult;
import com.pool.priority.PriorityKey;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * Task wrapper with priority information.
 * Extends FutureTask to provide both Future functionality and Runnable execution.
 * Implements Comparable for priority queue ordering.
 *
 * @param <T> Return type of the task
 */
public class PrioritizedTask<T> extends FutureTask<T> implements Comparable<PrioritizedTask<?>> {

    private final String taskId;
    private final TaskContext context;
    private final PriorityKey priorityKey;
    private final EvaluationResult evaluationResult;
    private final long submittedAt;

    /**
     * Create a prioritized callable task.
     */
    public PrioritizedTask(Callable<T> callable, TaskContext context, EvaluationResult evaluationResult) {
        super(callable);
        this.taskId = context.getTaskId();
        this.context = context;
        this.evaluationResult = evaluationResult;
        this.priorityKey = evaluationResult.getPriorityKey();
        this.submittedAt = context.getSubmittedAt();
    }

    /**
     * Create a prioritized runnable task.
     */
    public PrioritizedTask(Runnable runnable, TaskContext context, EvaluationResult evaluationResult) {
        super(runnable, null);
        this.taskId = context.getTaskId();
        this.context = context;
        this.evaluationResult = evaluationResult;
        this.priorityKey = evaluationResult.getPriorityKey();
        this.submittedAt = context.getSubmittedAt();
    }

    @Override
    public int compareTo(PrioritizedTask<?> other) {
        return this.priorityKey.compareTo(other.priorityKey);
    }

    public String getTaskId() {
        return taskId;
    }

    public TaskContext getContext() {
        return context;
    }

    public PriorityKey getPriorityKey() {
        return priorityKey;
    }

    public EvaluationResult getEvaluationResult() {
        return evaluationResult;
    }

    public long getSubmittedAt() {
        return submittedAt;
    }

    /**
     * Get wait time in milliseconds (time since submission).
     */
    public long getWaitTimeMs() {
        return System.currentTimeMillis() - submittedAt;
    }

    @Override
    public String toString() {
        return "PrioritizedTask{" +
                "taskId='" + taskId + '\'' +
                ", priority=" + priorityKey.getPathVector() +
                ", waitMs=" + getWaitTimeMs() +
                '}';
    }
}
