package com.pool.adapter.executor.tps;

import com.pool.core.PrioritizedPayload;
import com.pool.core.TaskContext;
import com.pool.exception.TaskRejectedException;
import com.pool.priority.PriorityKey;
import com.pool.strategy.PriorityStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages per-executor priority queues, capacity signaling, drainer threads,
 * and the shared thread pool for task execution.
 */
public class TaskQueueManager {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueManager.class);

    private final ExecutorHierarchy hierarchy;
    private final TpsGate tpsGate;

    // Per-executor priority strategies for task ordering and capacity
    private final Map<String, PriorityStrategy> executorStrategies;

    // Thread pool for execution (unbounded)
    private final ExecutorService threadPool;

    // Queue drainer threads (one per executor)
    private final Map<String, Thread> drainerThreads = new ConcurrentHashMap<>();

    // Per-executor locks and conditions for capacity signaling
    private final Map<String, java.util.concurrent.locks.ReentrantLock> capacityLocks;
    private final Map<String, java.util.concurrent.locks.Condition> capacityConditions;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger executedCount = new AtomicInteger(0);

    /**
     * Primary constructor — accepts pre-built queues and signaling infrastructure.
     * Used by TpsSystemConfig for centralized initialization.
     * Starts drainer threads for each executor.
     */
    public TaskQueueManager(ExecutorHierarchy hierarchy, TpsGate tpsGate,
                            Map<String, PriorityStrategy> executorStrategies,
                            Map<String, java.util.concurrent.locks.ReentrantLock> capacityLocks,
                            Map<String, java.util.concurrent.locks.Condition> capacityConditions,
                            ExecutorService threadPool) {
        this.hierarchy = hierarchy;
        this.tpsGate = tpsGate;
        this.executorStrategies = executorStrategies;
        this.capacityLocks = capacityLocks;
        this.capacityConditions = capacityConditions;
        this.threadPool = threadPool;

        startDrainers();
        log.info("TaskQueueManager initialized for {} executors", hierarchy.getAllExecutorIds().size());
    }

    private void startDrainers() {
        for (String executorId : hierarchy.getAllExecutorIds()) {
            Thread drainer = new Thread(() -> drainQueue(executorId),
                    "queue-drainer-" + executorId);
            drainer.setDaemon(true);
            drainer.start();
            drainerThreads.put(executorId, drainer);
        }
    }


    /**
     * Execute a task immediately on the thread pool.
     */
    public void executeTask(Runnable task, String requestId, String executorId) {
        activeCount.incrementAndGet();

        threadPool.submit(() -> {
            try {
                task.run();
                executedCount.incrementAndGet();
            } finally {
                activeCount.decrementAndGet();
            }
        });
    }

    /**
     * Queue a task for deferred execution when TPS capacity becomes available.
     */
    public void queueTask(Runnable task, String requestId, String executorId,
                           PriorityKey priorityKey, TaskContext context) {
        PriorityStrategy strategy = executorStrategies.get(executorId);
        if (strategy == null) {
            throw new TaskRejectedException("Unknown executor: " + executorId);
        }

        QueuedTask queuedTask = new QueuedTask(task, requestId, executorId, priorityKey, context);
        PrioritizedPayload<QueuedTask> payload = new PrioritizedPayload<>(queuedTask, requestId, priorityKey);

        if (!strategy.enqueue(payload)) {
            throw new TaskRejectedException("Queue full for executor '" + executorId +
                    "' (capacity: " + strategy.getCapacity() + "), task rejected: " + requestId);
        }

        log.debug("Task {} queued for executor '{}' (queue size: {}/{})",
                requestId, executorId, strategy.getQueueSize(),
                strategy.getCapacity() > 0 ? strategy.getCapacity() : "unbounded");
    }

    /**
     * Drains queued tasks when TPS capacity becomes available.
     * Uses dual-trigger: blocks on queue for new tasks, and wakes on capacity signal.
     */
    private void drainQueue(String executorId) {
        PriorityStrategy strategy = executorStrategies.get(executorId);
        java.util.concurrent.locks.ReentrantLock lock = capacityLocks.get(executorId);
        java.util.concurrent.locks.Condition capacityAvailable = capacityConditions.get(executorId);

        while (!shutdown.get()) {
            try {
                // Wait for items in queue
                Optional<PrioritizedPayload<?>> polled = strategy.pollNext(100, TimeUnit.MILLISECONDS);
                if (polled.isEmpty()) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                QueuedTask task = ((PrioritizedPayload<QueuedTask>) polled.get()).getPayload();

                // Hold the task and wait for capacity instead of re-inserting
                while (!shutdown.get() && !tpsGate.tryAcquire(task.context(), executorId)) {
                    // Wait for capacity signal or timeout as safety net
                    lock.lock();
                    try {
                        capacityAvailable.await(tpsGate.getWindowSizeMs(), TimeUnit.MILLISECONDS);
                    } finally {
                        lock.unlock();
                    }
                }

                if (!shutdown.get()) {
                    executeTask(task.task(), task.requestId(), executorId);
                    log.debug("Dequeued and executed task {} for executor '{}'",
                            task.requestId(), executorId);
                } else {
                    // Shutting down — put task back so it's not lost
                    strategy.enqueue(new PrioritizedPayload<>(task, task.requestId(), task.priorityKey()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Graceful shutdown — interrupts drainers and shuts down thread pool.
     */
    public void shutdown() {
        log.info("Shutting down TaskQueueManager");
        shutdown.set(true);
        for (Thread drainer : drainerThreads.values()) {
            drainer.interrupt();
        }
        threadPool.shutdown();
    }

    /**
     * Immediate shutdown.
     */
    public void shutdownNow() {
        log.info("Shutting down TaskQueueManager immediately");
        shutdown.set(true);
        for (Thread drainer : drainerThreads.values()) {
            drainer.interrupt();
        }
        threadPool.shutdownNow();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return threadPool.awaitTermination(timeout, unit);
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    public boolean isTerminated() {
        return shutdown.get() && threadPool.isTerminated();
    }

    public int getTotalQueueSize() {
        return executorStrategies.values().stream()
                .mapToInt(PriorityStrategy::getQueueSize)
                .sum();
    }

    public int getQueueSize(String executorId) {
        PriorityStrategy strategy = executorStrategies.get(executorId);
        return strategy != null ? strategy.getQueueSize() : 0;
    }

    public int getActiveCount() {
        return activeCount.get();
    }

    public int getExecutedCount() {
        return executedCount.get();
    }

    /**
     * Queued task wrapper with priority key for ordering.
     */
    public record QueuedTask(
            Runnable task,
            String requestId,
            String executorId,
            PriorityKey priorityKey,
            TaskContext context
    ) implements Comparable<QueuedTask> {

        @Override
        public int compareTo(QueuedTask other) {
            return this.priorityKey.compareTo(other.priorityKey);
        }
    }
}
