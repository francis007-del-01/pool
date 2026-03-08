package com.pool.adapter.executor.tps;

import com.pool.core.TaskContext;
import com.pool.exception.TaskRejectedException;
import com.pool.priority.PriorityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages per-executor priority queues, capacity signaling, drainer threads,
 * and the shared thread pool for task execution.
 */
@Component
public class TaskQueueManager {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueManager.class);

    private final ExecutorHierarchy hierarchy;
    private final TpsGate tpsGate;

    // Per-executor queues for priority ordering when TPS limit is hit
    private final Map<String, PriorityBlockingQueue<QueuedTask>> executorQueues;
    private final Map<String, AtomicInteger> executorQueueSizes;

    // Thread pool for execution (unbounded)
    private final ExecutorService threadPool;

    // Queue drainer threads (one per executor)
    private final Map<String, Thread> drainerThreads;

    // Per-executor locks and conditions for capacity signaling
    private final Map<String, ReentrantLock> capacityLocks;
    private final Map<String, Condition> capacityConditions;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger executedCount = new AtomicInteger(0);

    @Autowired
    public TaskQueueManager(ExecutorHierarchy hierarchy, TpsGate tpsGate) {
        this(hierarchy, tpsGate, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("tps-pool-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        }));
    }

    public TaskQueueManager(ExecutorHierarchy hierarchy, TpsGate tpsGate, ExecutorService threadPool) {
        this.hierarchy = hierarchy;
        this.tpsGate = tpsGate;
        this.threadPool = threadPool;

        // Initialize per-executor queues
        this.executorQueues = new ConcurrentHashMap<>();
        this.executorQueueSizes = new ConcurrentHashMap<>();
        for (String executorId : hierarchy.getAllExecutorIds()) {
            executorQueues.put(executorId, new PriorityBlockingQueue<>(
                    100, Comparator.comparing(QueuedTask::priorityKey)));
            executorQueueSizes.put(executorId, new AtomicInteger(0));
        }

        // Initialize per-executor capacity locks and conditions
        this.capacityLocks = new ConcurrentHashMap<>();
        this.capacityConditions = new ConcurrentHashMap<>();
        for (String executorId : hierarchy.getAllExecutorIds()) {
            ReentrantLock lock = new ReentrantLock();
            capacityLocks.put(executorId, lock);
            capacityConditions.put(executorId, lock.newCondition());

            // Register capacity callback: when TPS counter evicts, signal the drainer
            final String execId = executorId;
            tpsGate.onCapacityAvailable(executorId, () -> {
                ReentrantLock l = capacityLocks.get(execId);
                l.lock();
                try {
                    capacityConditions.get(execId).signalAll();
                } finally {
                    l.unlock();
                }
            });
        }

        // Start drainer threads for each executor
        this.drainerThreads = new ConcurrentHashMap<>();
        for (String executorId : hierarchy.getAllExecutorIds()) {
            Thread drainer = new Thread(() -> drainQueue(executorId),
                    "queue-drainer-" + executorId);
            drainer.setDaemon(true);
            drainer.start();
            drainerThreads.put(executorId, drainer);
        }

        log.info("TaskQueueManager initialized for {} executors", hierarchy.getAllExecutorIds().size());
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
        PriorityBlockingQueue<QueuedTask> queue = executorQueues.get(executorId);
        if (queue == null) {
            throw new TaskRejectedException("Unknown executor: " + executorId);
        }

        AtomicInteger queueSize = executorQueueSizes.get(executorId);
        int maxQueueCapacity = hierarchy.getQueueCapacity(executorId);

        // Check per-executor queue capacity
        if (maxQueueCapacity > 0 && queueSize.get() >= maxQueueCapacity) {
            throw new TaskRejectedException("Queue full for executor '" + executorId +
                    "' (capacity: " + maxQueueCapacity + "), task rejected: " + requestId);
        }

        QueuedTask queuedTask = new QueuedTask(task, requestId, executorId, priorityKey, context);
        queue.offer(queuedTask);
        queueSize.incrementAndGet();

        log.debug("Task {} queued for executor '{}' (queue size: {}/{})",
                requestId, executorId, queueSize.get(), maxQueueCapacity > 0 ? maxQueueCapacity : "unbounded");
    }

    /**
     * Drains queued tasks when TPS capacity becomes available.
     * Uses dual-trigger: blocks on queue for new tasks, and wakes on capacity signal.
     */
    private void drainQueue(String executorId) {
        PriorityBlockingQueue<QueuedTask> queue = executorQueues.get(executorId);
        ReentrantLock lock = capacityLocks.get(executorId);
        Condition capacityAvailable = capacityConditions.get(executorId);

        while (!shutdown.get()) {
            try {
                // Wait for items in queue
                QueuedTask task = queue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }

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
                    executorQueueSizes.get(executorId).decrementAndGet();
                    executeTask(task.task(), task.requestId(), executorId);
                    log.debug("Dequeued and executed task {} for executor '{}'",
                            task.requestId(), executorId);
                } else {
                    // Shutting down — put task back so it's not lost
                    queue.offer(task);
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
        return executorQueueSizes.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }

    public int getQueueSize(String executorId) {
        PriorityBlockingQueue<QueuedTask> queue = executorQueues.get(executorId);
        return queue != null ? queue.size() : 0;
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
    record QueuedTask(
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
