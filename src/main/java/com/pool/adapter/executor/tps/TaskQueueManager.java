package com.pool.adapter.executor.tps;

import com.pool.config.ExecutorHierarchy;
import com.pool.core.PrioritizedPayload;
import com.pool.core.TaskContext;
import com.pool.exception.TaskRejectedException;
import com.pool.exception.TpsExceededException;
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
 *
 * Supports two modes:
 * 1. Fire-and-forget: executeTask / queueTask for Runnable tasks
 * 2. Blocking admission: queueAndAwait for the AOP aspect — caller blocks
 *    on a CompletableFuture until the drainer acquires TPS capacity
 */
public class TaskQueueManager {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueManager.class);

    private final ExecutorHierarchy hierarchy;
    private final TpsGate tpsGate;
    private final Map<String, PriorityStrategy<QueuedTask>> executorStrategies;
    private final ExecutorService threadPool;
    private final Map<String, Thread> drainerThreads = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.locks.ReentrantLock> capacityLocks;
    private final Map<String, java.util.concurrent.locks.Condition> capacityConditions;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger executedCount = new AtomicInteger(0);

    public TaskQueueManager(ExecutorHierarchy hierarchy, TpsGate tpsGate,
                            Map<String, PriorityStrategy<QueuedTask>> executorStrategies,
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
        for (String rootId : hierarchy.getRootIds()) {
            Thread drainer = new Thread(() -> drainQueue(rootId),
                    "queue-drainer-" + rootId);
            drainer.setDaemon(true);
            drainer.start();
            drainerThreads.put(rootId, drainer);
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
     * Fire-and-forget mode — task runs on thread pool when dequeued.
     */
    public void queueTask(Runnable task, String requestId, String executorId,
                           PriorityKey priorityKey, TaskContext context) {
        PriorityStrategy<QueuedTask> strategy = getStrategy(hierarchy.getRootIdFor(executorId));

        QueuedTask queuedTask = new QueuedTask(task, requestId, executorId, priorityKey, context, null);
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
     * Queue a request and return a CompletableFuture that completes when TPS is acquired.
     * Used by the AOP aspect — the caller blocks on the future with a timeout.
     * When the drainer acquires TPS, it completes the future, and the caller proceeds.
     *
     * @return CompletableFuture that completes when admitted, or exceptionally on queue full
     */
    public CompletableFuture<Void> queueAndAwait(String executorId, PriorityKey priorityKey, TaskContext context) {
        PriorityStrategy<QueuedTask> strategy = getStrategy(hierarchy.getRootIdFor(executorId));

        CompletableFuture<Void> future = new CompletableFuture<>();
        String requestId = context.getTaskId();

        QueuedTask queuedTask = new QueuedTask(null, requestId, executorId, priorityKey, context, future);
        PrioritizedPayload<QueuedTask> payload = new PrioritizedPayload<>(queuedTask, requestId, priorityKey);

        if (!strategy.enqueue(payload)) {
            future.completeExceptionally(new TaskRejectedException(
                    "Queue full for executor '" + executorId + "' (capacity: " + strategy.getCapacity() + ")"));
        }

        log.debug("Request {} queued for admission to executor '{}' (queue size: {})",
                requestId, executorId, strategy.getQueueSize());

        return future;
    }

    /**
     * Drains queued tasks when TPS capacity becomes available.
     */
    private void drainQueue(String executorId) {
        PriorityStrategy<QueuedTask> strategy = executorStrategies.get(executorId);
        java.util.concurrent.locks.ReentrantLock lock = capacityLocks.get(executorId);
        java.util.concurrent.locks.Condition capacityAvailable = capacityConditions.get(executorId);

        while (!shutdown.get()) {
            try {
                Optional<PrioritizedPayload<QueuedTask>> polled = strategy.pollNext(100, TimeUnit.MILLISECONDS);
                if (polled.isEmpty()) continue;

                QueuedTask task = polled.get().getPayload();

                while (!shutdown.get() && !tpsGate.tryAcquire(task.executorId())) {
                    lock.lock();
                    try {
                        capacityAvailable.await(tpsGate.getWindowSizeMs(), TimeUnit.MILLISECONDS);
                    } finally {
                        lock.unlock();
                    }

                    // Check if the future has been cancelled/timed out by the caller
                    if (task.admissionFuture() != null && task.admissionFuture().isDone()) {
                        log.debug("Queued request {} cancelled/timed out, discarding", task.requestId());
                        break;
                    }
                }

                if (shutdown.get()) {
                    strategy.enqueue(new PrioritizedPayload<>(task, task.requestId(), task.priorityKey()));
                    continue;
                }

                // TPS acquired — either complete the future or execute the task
                if (task.admissionFuture() != null) {
                    if (!task.admissionFuture().isDone()) {
                        task.admissionFuture().complete(null);
                        log.debug("Admission granted for queued request {} on executor '{}'",
                                task.requestId(), executorId);
                    }
                } else if (task.task() != null) {
                    executeTask(task.task(), task.requestId(), executorId);
                    log.debug("Dequeued and executed task {} for executor '{}'",
                            task.requestId(), executorId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private PriorityStrategy<QueuedTask> getStrategy(String executorId) {
        PriorityStrategy<QueuedTask> strategy = executorStrategies.get(executorId);
        if (strategy == null) {
            throw new TaskRejectedException("Unknown executor: " + executorId);
        }
        return strategy;
    }

    public void shutdown() {
        log.info("Shutting down TaskQueueManager");
        shutdown.set(true);
        for (Thread drainer : drainerThreads.values()) {
            drainer.interrupt();
        }
        threadPool.shutdown();
    }

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
        PriorityStrategy<QueuedTask> strategy = executorStrategies.get(hierarchy.getRootIdFor(executorId));
        return strategy != null ? strategy.getQueueSize() : 0;
    }

    public int getActiveCount() {
        return activeCount.get();
    }

    public int getExecutedCount() {
        return executedCount.get();
    }

    /**
     * Queued task wrapper. Supports both fire-and-forget (task != null)
     * and blocking admission (admissionFuture != null) modes.
     */
    public record QueuedTask(
            Runnable task,
            String requestId,
            String executorId,
            PriorityKey priorityKey,
            TaskContext context,
            CompletableFuture<Void> admissionFuture
    ) implements Comparable<QueuedTask> {

        @Override
        public int compareTo(QueuedTask other) {
            return this.priorityKey.compareTo(other.priorityKey);
        }
    }
}
