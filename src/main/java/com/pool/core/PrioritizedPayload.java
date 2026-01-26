package com.pool.core;

import com.pool.priority.PriorityKey;

import java.util.Objects;

/**
 * Payload wrapper with priority metadata for scheduling.
 *
 * @param <T> Payload type
 */
public final class PrioritizedPayload<T> implements Comparable<PrioritizedPayload<?>> {

    private final T payload;
    private final PriorityKey priorityKey;
    private final String taskId;

    public PrioritizedPayload(T payload, String taskId, PriorityKey priorityKey) {
        this.payload = payload;
        this.taskId = Objects.requireNonNull(taskId, "taskId cannot be null");
        this.priorityKey = Objects.requireNonNull(priorityKey, "priorityKey cannot be null");
    }

    public T getPayload() {
        return payload;
    }

    public String getTaskId() {
        return taskId;
    }

    public PriorityKey getPriorityKey() {
        return priorityKey;
    }

    @Override
    public int compareTo(PrioritizedPayload<?> other) {
        return this.priorityKey.compareTo(other.priorityKey);
    }

    @Override
    public String toString() {
        return "PrioritizedPayload{" +
                "taskId='" + taskId + '\'' +
                ", priority=" + priorityKey.getPathVector() +
                '}';
    }
}
