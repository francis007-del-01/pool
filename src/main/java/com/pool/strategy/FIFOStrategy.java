package com.pool.strategy;

import com.pool.core.PrioritizedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FIFO Priority Strategy.
 * <p>
 * Simple priority queue implementation:
 * - Single PriorityBlockingQueue
 * - Tasks ordered by: (1) PathVector, (2) SortValue, (3) SubmittedAt
 * - Oldest task with highest priority wins
 * - No daemon threads, zero overhead
 * <p>
 * Best for:
 * - Most use cases
 * - Low-latency requirements
 * - Simple debugging and reasoning
 */
public class FIFOStrategy<T> implements PriorityStrategy<T> {

    private static final Logger log = LoggerFactory.getLogger(FIFOStrategy.class);

    private final int capacity;
    private final PriorityBlockingQueue<PrioritizedPayload<T>> queue;
    private final Semaphore capacitySemaphore;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public FIFOStrategy(int capacity) {
        this.capacity = capacity;
        this.queue = new PriorityBlockingQueue<>(
                Math.max(1, Math.min(capacity, 1000)), // Initial capacity (min 1 required by PBQ)
                (t1, t2) -> t1.compareTo(t2)
        );
        this.capacitySemaphore = new Semaphore(capacity);
        log.info("FIFOStrategy initialized with capacity: {}", capacity);
    }

    @Override
    public String getName() {
        return "FIFO";
    }

    @Override
    public boolean enqueue(PrioritizedPayload<T> task) {
        if (shutdown.get()) {
            log.warn("Strategy is shutdown, rejecting task: {}", task.getTaskId());
            return false;
        }

        // Enforce capacity atomically (PriorityBlockingQueue is unbounded)
        if (!capacitySemaphore.tryAcquire()) {
            log.warn("Queue at capacity ({}), rejecting task: {}", capacity, task.getTaskId());
            return false;
        }

        boolean added = queue.offer(task);
        if (!added) {
            capacitySemaphore.release();
        }
        if (added) {
            log.trace("Task {} enqueued, queue size: {}", task.getTaskId(), queue.size());
        }
        return added;
    }

    @Override
    public PrioritizedPayload<T> takeNext() throws InterruptedException {
        while (!shutdown.get()) {
            PrioritizedPayload<T> task = queue.poll(100, TimeUnit.MILLISECONDS);
            if (task != null) {
                capacitySemaphore.release();
                log.trace("Task {} dequeued (blocking), queue size: {}", task.getTaskId(), queue.size());
                return task;
            }
        }
        throw new InterruptedException("Strategy has been shut down");
    }

    @Override
    public Optional<PrioritizedPayload<T>> pollNext() {
        PrioritizedPayload<T> task = queue.poll();
        if (task != null) {
            capacitySemaphore.release();
            log.trace("Task {} dequeued (poll), queue size: {}", task.getTaskId(), queue.size());
        }
        return Optional.ofNullable(task);
    }

    @Override
    public Optional<PrioritizedPayload<T>> pollNext(long timeout, TimeUnit unit) throws InterruptedException {
        PrioritizedPayload<T> task = queue.poll(timeout, unit);
        if (task != null) {
            capacitySemaphore.release();
            log.trace("Task {} dequeued (timed poll), queue size: {}", task.getTaskId(), queue.size());
        }
        return Optional.ofNullable(task);
    }

    @Override
    public int getQueueSize() {
        return queue.size();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public int getRemainingCapacity() {
        return capacitySemaphore.availablePermits();
    }

    @Override
    public void shutdown() {
        shutdown.set(true);
        log.info("FIFOStrategy shutdown, {} tasks remaining in queue", queue.size());
    }
}
