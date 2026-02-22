package com.pool.adapter.executor.tps;

import com.pool.config.PoolConfig;
import com.pool.core.TaskContext;
import com.pool.exception.TaskRejectedException;
import com.pool.policy.EvaluationResult;
import com.pool.policy.PolicyEngine;
import com.pool.policy.PolicyEngineFactory;
import com.pool.priority.PriorityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TPS-based executor with hierarchical executor support.
 * Replaces DefaultPoolExecutor with TPS-gated admission and unbounded threads.
 */
public class TpsPoolExecutor implements com.pool.adapter.executor.PoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TpsPoolExecutor.class);

    private final PoolConfig config;
    private final PolicyEngine policyEngine;
    private final ExecutorHierarchy hierarchy;
    private final TpsGate tpsGate;

    // Per-executor queues for priority ordering when TPS limit is hit
    private final Map<String, PriorityBlockingQueue<QueuedTask>> executorQueues;
    private final Map<String, AtomicInteger> executorQueueSizes;

    // Thread pool for execution (unbounded)
    private final ExecutorService threadPool;

    // Queue drainer threads (one per executor)
    private final Map<String, Thread> drainerThreads;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger submittedCount = new AtomicInteger(0);
    private final AtomicInteger executedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final AtomicInteger activeCount = new AtomicInteger(0);

    public TpsPoolExecutor(PoolConfig config) {
        this.config = config;
        this.policyEngine = PolicyEngineFactory.create(config);

        // Build executor hierarchy
        this.hierarchy = new ExecutorHierarchy(config.executors());
        this.tpsGate = new TpsGate(hierarchy);

        // Initialize per-executor queues with their own capacities
        this.executorQueues = new ConcurrentHashMap<>();
        this.executorQueueSizes = new ConcurrentHashMap<>();
        for (String executorId : hierarchy.getAllExecutorIds()) {
            executorQueues.put(executorId, new PriorityBlockingQueue<>(
                    100, Comparator.comparing(QueuedTask::priorityKey)));
            executorQueueSizes.put(executorId, new AtomicInteger(0));
        }

        // Unbounded cached thread pool
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("tps-pool-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        // Start drainer threads for each executor
        this.drainerThreads = new ConcurrentHashMap<>();
        for (String executorId : hierarchy.getAllExecutorIds()) {
            Thread drainer = new Thread(() -> drainQueue(executorId), 
                    "queue-drainer-" + executorId);
            drainer.setDaemon(true);
            drainer.start();
            drainerThreads.put(executorId, drainer);
        }

        log.info("TpsPoolExecutor initialized: {} with {} executors, root TPS: {}",
                config.name(),
                config.executors().size(),
                hierarchy.getTps(hierarchy.getRootId()));
    }

    @Override
    public void submit(TaskContext context, Runnable task) {
        if (context == null) {
            throw new NullPointerException("Context cannot be null");
        }
        if (task == null) {
            throw new NullPointerException("Task cannot be null");
        }
        if (shutdown.get()) {
            throw new TaskRejectedException("Executor is shutdown");
        }

        // Evaluate priority and get target executor
        EvaluationResult result = policyEngine.evaluate(context);
        String executorId = result.getMatchedPath().executor();
        
        // Use default executor if none specified
        if (executorId == null || executorId.isEmpty()) {
            executorId = hierarchy.getRootId();
        }

        PriorityKey priorityKey = result.getPriorityKey();
        String requestId = context.getTaskId();

        submittedCount.incrementAndGet();

        // Try to acquire TPS (uses per-executor identifier field)
        if (tpsGate.tryAcquire(context, executorId)) {
            // Under TPS limit - execute immediately
            executeTask(task, requestId, executorId);
            log.debug("Task {} executed immediately (executor: {}, TPS: {}/{})",
                    requestId, executorId, 
                    tpsGate.getCurrentTps(executorId), 
                    hierarchy.getTps(executorId));
        } else {
            // TPS limit hit - queue the task
            queueTask(task, requestId, executorId, priorityKey, context);
        }
    }

    @Override
    public <T> Future<T> submit(TaskContext context, Callable<T> task) {
        if (context == null) {
            throw new NullPointerException("Context cannot be null");
        }
        if (task == null) {
            throw new NullPointerException("Task cannot be null");
        }
        if (shutdown.get()) {
            throw new TaskRejectedException("Executor is shutdown");
        }

        FutureTask<T> futureTask = new FutureTask<>(task);
        
        // Evaluate priority and get target executor
        EvaluationResult result = policyEngine.evaluate(context);
        String executorId = result.getMatchedPath().executor();
        
        if (executorId == null || executorId.isEmpty()) {
            executorId = hierarchy.getRootId();
        }

        PriorityKey priorityKey = result.getPriorityKey();
        String requestId = context.getTaskId();

        submittedCount.incrementAndGet();

        // Try to acquire TPS (uses per-executor identifier field)
        if (tpsGate.tryAcquire(context, executorId)) {
            executeTask(futureTask, requestId, executorId);
        } else {
            queueTask(futureTask, requestId, executorId, priorityKey, context);
        }

        return futureTask;
    }

    private void executeTask(Runnable task, String requestId, String executorId) {
        activeCount.incrementAndGet();
        
        threadPool.submit(() -> {
            try {
                task.run();
                executedCount.incrementAndGet();
            } finally {
                activeCount.decrementAndGet();
                // TPS window will auto-expire, no explicit release needed
            }
        });
    }

    private void queueTask(Runnable task, String requestId, String executorId, 
                           PriorityKey priorityKey, TaskContext context) {
        PriorityBlockingQueue<QueuedTask> queue = executorQueues.get(executorId);
        if (queue == null) {
            rejectedCount.incrementAndGet();
            throw new TaskRejectedException("Unknown executor: " + executorId);
        }

        AtomicInteger queueSize = executorQueueSizes.get(executorId);
        int maxQueueCapacity = hierarchy.getQueueCapacity(executorId);
        
        // Check per-executor queue capacity
        if (maxQueueCapacity > 0 && queueSize.get() >= maxQueueCapacity) {
            rejectedCount.incrementAndGet();
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
     */
    private void drainQueue(String executorId) {
        PriorityBlockingQueue<QueuedTask> queue = executorQueues.get(executorId);
        
        while (!shutdown.get()) {
            try {
                // Wait for items in queue
                QueuedTask task = queue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }

                // Check if we have TPS capacity (use TaskContext for per-executor identifiers)
                if (tpsGate.tryAcquire(task.context(), executorId)) {
                    executorQueueSizes.get(executorId).decrementAndGet();
                    executeTask(task.task(), task.requestId(), executorId);
                    
                    log.debug("Dequeued and executed task {} for executor '{}'",
                            task.requestId(), executorId);
                } else {
                    // Put back in queue if no capacity
                    queue.offer(task);
                    // Small backoff to avoid busy-waiting
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void shutdown() {
        log.info("Shutting down TpsPoolExecutor: {}", config.name());
        shutdown.set(true);
        
        // Interrupt drainer threads
        for (Thread drainer : drainerThreads.values()) {
            drainer.interrupt();
        }
        
        threadPool.shutdown();
    }

    @Override
    public void shutdownNow() {
        log.info("Shutting down TpsPoolExecutor immediately: {}", config.name());
        shutdown.set(true);
        
        for (Thread drainer : drainerThreads.values()) {
            drainer.interrupt();
        }
        
        threadPool.shutdownNow();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return threadPool.awaitTermination(timeout, unit);
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean isTerminated() {
        return shutdown.get() && threadPool.isTerminated();
    }

    @Override
    public int getQueueSize() {
        return executorQueueSizes.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }

    @Override
    public int getActiveCount() {
        return activeCount.get();
    }

    /**
     * Get queue size for a specific executor.
     */
    public int getQueueSize(String executorId) {
        PriorityBlockingQueue<QueuedTask> queue = executorQueues.get(executorId);
        return queue != null ? queue.size() : 0;
    }

    /**
     * Get current TPS for an executor.
     */
    public int getCurrentTps(String executorId) {
        return tpsGate.getCurrentTps(executorId);
    }

    /**
     * Get available TPS capacity for an executor.
     */
    public int getAvailableTps(String executorId) {
        return tpsGate.getAvailableCapacity(executorId);
    }

    /**
     * Get the executor hierarchy.
     */
    public ExecutorHierarchy getHierarchy() {
        return hierarchy;
    }

    /**
     * Get the TPS gate.
     */
    public TpsGate getTpsGate() {
        return tpsGate;
    }

    /**
     * Get statistics.
     */
    public TpsExecutorStats getStats() {
        return new TpsExecutorStats(
                submittedCount.get(),
                executedCount.get(),
                rejectedCount.get(),
                getQueueSize(),
                activeCount.get(),
                hierarchy.getTps(hierarchy.getRootId()),
                tpsGate.getCurrentTps(hierarchy.getRootId())
        );
    }

    /**
     * Queued task wrapper with priority key for ordering.
     */
    private record QueuedTask(
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

    /**
     * Executor statistics.
     */
    public record TpsExecutorStats(
            int submitted,
            int executed,
            int rejected,
            int queueSize,
            int activeThreads,
            int maxTps,
            int currentTps
    ) {}
}
