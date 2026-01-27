package com.pool.adapter.executor;

import com.pool.config.ExecutorSpec;
import com.pool.config.PoolConfig;
import com.pool.core.TaskContext;
import com.pool.exception.TaskRejectedException;
import com.pool.policy.DefaultPolicyEngine;
import com.pool.policy.PolicyEngine;
import com.pool.scheduler.DefaultPriorityScheduler;
import com.pool.scheduler.PriorityScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation of PoolExecutor.
 * Supports multiple queues, each with its own worker pool based on ExecutorSpec.
 */
public class DefaultPoolExecutor implements PoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultPoolExecutor.class);

    private final PoolConfig config;
    private final PolicyEngine policyEngine;
    private final PriorityScheduler<ExecutableTask> priorityScheduler;
    
    // Per-queue worker management
    private final Map<String, List<WorkerThread>> workersByQueue;
    private final Map<String, AtomicInteger> workerCountByQueue;
    private final Map<String, AtomicInteger> activeCountByQueue;
    private final Map<String, AtomicInteger> completedCountByQueue;
    
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger submittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);

    public DefaultPoolExecutor(PoolConfig config) {
        this.config = config;
        this.policyEngine = new DefaultPolicyEngine(config);
        this.priorityScheduler = new DefaultPriorityScheduler<>(config, policyEngine);
        
        // Initialize per-queue structures
        this.workersByQueue = new HashMap<>();
        this.workerCountByQueue = new HashMap<>();
        this.activeCountByQueue = new HashMap<>();
        this.completedCountByQueue = new HashMap<>();
        
        // Create workers for each executor spec
        for (ExecutorSpec spec : config.executors()) {
            String queueName = spec.queue().name();
            
            workersByQueue.put(queueName, new ArrayList<>());
            workerCountByQueue.put(queueName, new AtomicInteger(0));
            activeCountByQueue.put(queueName, new AtomicInteger(0));
            completedCountByQueue.put(queueName, new AtomicInteger(0));
            
            // Start core workers for this queue
            for (int i = 0; i < spec.corePoolSize(); i++) {
                startWorker(queueName, spec, i + 1, true);
            }
            
            log.info("Initialized executor for queue '{}' (core={}, max={}, capacity={})",
                    queueName, spec.corePoolSize(), spec.maxPoolSize(), spec.queue().capacity());
        }
        
        log.info("PoolExecutor initialized: {} with {} queues", config.name(), config.executors().size());
    }

    private void startWorker(String queueName, ExecutorSpec spec, int workerId, boolean isCoreThread) {
        WorkerThread worker = new WorkerThread(
                workerId,
                isCoreThread,
                queueName,
                spec,
                priorityScheduler,
                shutdown,
                activeCountByQueue.get(queueName),
                completedCountByQueue.get(queueName),
                this::removeWorker,
                log
        );
        
        synchronized (workersByQueue) {
            workersByQueue.get(queueName).add(worker);
        }
        workerCountByQueue.get(queueName).incrementAndGet();
        worker.start();
        
        log.debug("Started {} worker {} for queue '{}'", 
                isCoreThread ? "core" : "excess", worker.getName(), queueName);
    }

    private void removeWorker(WorkerThread worker) {
        String queueName = worker.getQueueName();
        synchronized (workersByQueue) {
            List<WorkerThread> workers = workersByQueue.get(queueName);
            if (workers != null) {
                workers.remove(worker);
            }
        }
        AtomicInteger count = workerCountByQueue.get(queueName);
        if (count != null) {
            count.decrementAndGet();
        }
        log.debug("Removed worker {} from queue '{}'", worker.getName(), queueName);
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

        FutureTask<Void> futureTask = new FutureTask<>(task, null);
        ExecutableTask executableTask = new ExecutableTask(futureTask, context.getTaskId());

        String queueName = priorityScheduler.submit(context, executableTask);
        if (queueName == null) {
            rejectedCount.incrementAndGet();
            throw new TaskRejectedException("Queue is full, task rejected: " + context.getTaskId());
        }

        submittedCount.incrementAndGet();
        log.debug("Task {} submitted to queue '{}' (queue size: {})",
                context.getTaskId(), queueName, priorityScheduler.size(queueName));

        // Scale up workers for this queue if needed
        maybeScaleUp(queueName);
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
        ExecutableTask executableTask = new ExecutableTask(futureTask, context.getTaskId());

        String queueName = priorityScheduler.submit(context, executableTask);
        if (queueName == null) {
            rejectedCount.incrementAndGet();
            throw new TaskRejectedException("Queue is full, task rejected: " + context.getTaskId());
        }

        submittedCount.incrementAndGet();
        log.debug("Task {} submitted to queue '{}' (queue size: {})",
                context.getTaskId(), queueName, priorityScheduler.size(queueName));

        // Scale up workers for this queue if needed
        maybeScaleUp(queueName);

        return futureTask;
    }

    /**
     * Scale up workers for a specific queue if needed.
     */
    private void maybeScaleUp(String queueName) {
        ExecutorSpec spec = config.getExecutorByQueue(queueName);
        if (spec == null) {
            return;
        }
        
        AtomicInteger workerCount = workerCountByQueue.get(queueName);
        int currentWorkers = workerCount != null ? workerCount.get() : 0;
        int queueSize = priorityScheduler.size(queueName);

        if (queueSize > 0 && currentWorkers < spec.maxPoolSize()) {
            synchronized (workersByQueue) {
                int count = workerCountByQueue.get(queueName).get();
                if (count < spec.maxPoolSize() && !shutdown.get()) {
                    startWorker(queueName, spec, count + 1, false);
                }
            }
        }
    }

    @Override
    public void shutdown() {
        log.info("Shutting down PoolExecutor: {}", config.name());
        shutdown.set(true);
        priorityScheduler.shutdown();

        // Interrupt all workers
        for (List<WorkerThread> workers : workersByQueue.values()) {
            for (WorkerThread worker : workers) {
                worker.interrupt();
            }
        }
    }

    @Override
    public void shutdownNow() {
        log.info("Shutting down PoolExecutor immediately: {}", config.name());
        shutdown.set(true);
        priorityScheduler.shutdown();

        // Interrupt all workers
        for (List<WorkerThread> workers : workersByQueue.values()) {
            for (WorkerThread worker : workers) {
                worker.interrupt();
            }
        }
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        
        for (List<WorkerThread> workers : workersByQueue.values()) {
            for (WorkerThread worker : workers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                worker.join(TimeUnit.NANOSECONDS.toMillis(remaining));
                if (worker.isAlive()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        if (!shutdown.get()) {
            return false;
        }
        for (List<WorkerThread> workers : workersByQueue.values()) {
            for (WorkerThread worker : workers) {
                if (worker.isAlive()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int getQueueSize() {
        return priorityScheduler.size();
    }

    @Override
    public int getActiveCount() {
        return activeCountByQueue.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }

    /**
     * Get queue size for a specific queue.
     */
    public int getQueueSize(String queueName) {
        return priorityScheduler.size(queueName);
    }

    /**
     * Get active count for a specific queue.
     */
    public int getActiveCount(String queueName) {
        AtomicInteger count = activeCountByQueue.get(queueName);
        return count != null ? count.get() : 0;
    }

    /**
     * Get worker count for a specific queue.
     */
    public int getWorkerCount(String queueName) {
        AtomicInteger count = workerCountByQueue.get(queueName);
        return count != null ? count.get() : 0;
    }

    /**
     * Get total worker count across all queues.
     */
    public int getWorkerCount() {
        return workerCountByQueue.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }

    /**
     * Get statistics about the executor.
     */
    public ExecutorStats getStats() {
        int totalCompleted = completedCountByQueue.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
        int totalMaxPool = config.executors().stream()
                .mapToInt(ExecutorSpec::maxPoolSize)
                .sum();
        
        return new ExecutorStats(
                submittedCount.get(),
                totalCompleted,
                rejectedCount.get(),
                priorityScheduler.size(),
                getActiveCount(),
                getWorkerCount(),
                totalMaxPool,
                "MULTI_QUEUE"
        );
    }

    /**
     * Get the underlying policy engine.
     */
    public PolicyEngine getPolicyEngine() {
        return policyEngine;
    }

    /**
     * Get the underlying priority scheduler.
     */
    public PriorityScheduler<ExecutableTask> getPriorityScheduler() {
        return priorityScheduler;
    }

    /**
     * Executor statistics.
     */
    public record ExecutorStats(
            int submittedCount,
            int completedCount,
            int rejectedCount,
            int queueSize,
            int activeThreads,
            int poolSize,
            int maxPoolSize,
            String strategyName
    ) {}
}
