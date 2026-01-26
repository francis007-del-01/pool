package com.pool.core;

import com.pool.config.ExecutorConfig;
import com.pool.config.PoolConfig;
import com.pool.exception.TaskRejectedException;
import com.pool.policy.DefaultPolicyEngine;
import com.pool.policy.EvaluationResult;
import com.pool.policy.PolicyEngine;
import com.pool.strategy.PriorityStrategy;
import com.pool.strategy.PriorityStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation of PoolExecutor.
 * Uses a pluggable PriorityStrategy for task ordering and selection.
 */
public class DefaultPoolExecutor implements PoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultPoolExecutor.class);

    private final PoolConfig config;
    private final PolicyEngine policyEngine;
    private final PriorityStrategy priorityStrategy;
    private final List<WorkerThread> workers;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger submittedCount = new AtomicInteger(0);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private final Semaphore workerSemaphore;
    private final ExecutorConfig execConfig;

    public DefaultPoolExecutor(PoolConfig config) {
        this.config = config;
        this.policyEngine = new DefaultPolicyEngine(config);

        this.execConfig = config.executor() != null
                ? config.executor()
                : ExecutorConfig.defaults();

        // Create priority strategy
        this.priorityStrategy = PriorityStrategyFactory.create(
                config.priorityStrategy(),
                execConfig.queueCapacity()
        );

        // Create worker threads
        this.workers = new ArrayList<>();
        this.workerSemaphore = new Semaphore(execConfig.maxPoolSize());

        // Start core worker threads
        for (int i = 0; i < execConfig.corePoolSize(); i++) {
            startWorker(i + 1, true);  // Core threads never time out
        }

        log.info("PoolExecutor initialized: {} (strategy={}, core={}, max={}, queueCapacity={})",
                config.name(), priorityStrategy.getName(),
                execConfig.corePoolSize(), execConfig.maxPoolSize(),
                execConfig.queueCapacity());
    }

    private void startWorker(int workerId, boolean isCoreThread) {
        WorkerThread worker = new WorkerThread(workerId, isCoreThread);
        synchronized (workers) {
            workers.add(worker);
        }
        workerCount.incrementAndGet();
        worker.start();
        log.debug("Started {} worker thread: {}", isCoreThread ? "core" : "excess", worker.getName());
    }

    private void removeWorker(WorkerThread worker) {
        synchronized (workers) {
            workers.remove(worker);
        }
        workerCount.decrementAndGet();
        log.debug("Removed worker thread: {}", worker.getName());
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

        // Evaluate priority
        EvaluationResult result = policyEngine.evaluate(context);

        // Create prioritized task
        PrioritizedTask<?> prioritizedTask = new PrioritizedTask<>(task, context, result);

        // Enqueue to strategy
        if (!priorityStrategy.enqueue(prioritizedTask)) {
            rejectedCount.incrementAndGet();
            throw new TaskRejectedException("Queue is full, task rejected: " + context.getTaskId());
        }

        submittedCount.incrementAndGet();
        log.debug("Task {} submitted with priority {} (queue size: {})",
                context.getTaskId(), result.getPriorityKey().getPathVector(),
                priorityStrategy.getQueueSize());

        // Scale up workers if needed
        maybeScaleUp();
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

        // Evaluate priority
        EvaluationResult result = policyEngine.evaluate(context);

        // Create prioritized task
        PrioritizedTask<T> prioritizedTask = new PrioritizedTask<>(task, context, result);

        // Enqueue to strategy
        if (!priorityStrategy.enqueue(prioritizedTask)) {
            rejectedCount.incrementAndGet();
            throw new TaskRejectedException("Queue is full, task rejected: " + context.getTaskId());
        }

        submittedCount.incrementAndGet();
        log.debug("Task {} submitted with priority {} (queue size: {})",
                context.getTaskId(), result.getPriorityKey().getPathVector(),
                priorityStrategy.getQueueSize());

        // Scale up workers if needed
        maybeScaleUp();

        return prioritizedTask;
    }

    /**
     * Scale up workers if queue has items and we haven't hit max.
     */
    private void maybeScaleUp() {
        int currentWorkers = workerCount.get();
        int queueSize = priorityStrategy.getQueueSize();

        // If queue has items and we have room for more workers
        if (queueSize > 0 && currentWorkers < execConfig.maxPoolSize()) {
            synchronized (workers) {
                if (workerCount.get() < execConfig.maxPoolSize() && !shutdown.get()) {
                    startWorker(workerCount.get() + 1, false);  // Excess threads can time out
                }
            }
        }
    }

    @Override
    public void shutdown() {
        log.info("Shutting down PoolExecutor: {}", config.name());
        shutdown.set(true);
        priorityStrategy.shutdown();

        // Interrupt workers waiting on queue
        for (WorkerThread worker : workers) {
            worker.interrupt();
        }
    }

    @Override
    public void shutdownNow() {
        log.info("Shutting down PoolExecutor immediately: {}", config.name());
        shutdown.set(true);
        priorityStrategy.shutdown();

        // Interrupt all workers
        for (WorkerThread worker : workers) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
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
        return true;
    }

    @Override
    public boolean isTerminated() {
        if (!shutdown.get()) {
            return false;
        }
        for (WorkerThread worker : workers) {
            if (worker.isAlive()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getQueueSize() {
        return priorityStrategy.getQueueSize();
    }

    @Override
    public int getActiveCount() {
        return activeCount.get();
    }

    /**
     * Get the priority strategy being used.
     */
    public PriorityStrategy getPriorityStrategy() {
        return priorityStrategy;
    }

    /**
     * Get statistics about the executor.
     */
    public ExecutorStats getStats() {
        return new ExecutorStats(
                submittedCount.get(),
                completedCount.get(),
                rejectedCount.get(),
                priorityStrategy.getQueueSize(),
                activeCount.get(),
                workerCount.get(),
                execConfig.maxPoolSize(),
                priorityStrategy.getName()
        );
    }

    /**
     * Get current worker count.
     */
    public int getWorkerCount() {
        return workerCount.get();
    }

    /**
     * Get the underlying policy engine.
     */
    public PolicyEngine getPolicyEngine() {
        return policyEngine;
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

    /**
     * Worker thread that pulls tasks from the priority strategy.
     * Core threads block indefinitely; excess threads time out after keep-alive period.
     */
    private class WorkerThread extends Thread {

        private final int workerId;
        private final boolean isCoreThread;

        WorkerThread(int workerId, boolean isCoreThread) {
            super(execConfig.threadNamePrefix() + workerId);
            this.workerId = workerId;
            this.isCoreThread = isCoreThread;
            setDaemon(false);
        }

        @Override
        public void run() {
            log.debug("Worker {} started (core={})", workerId, isCoreThread);

            while (!shutdown.get()) {
                try {
                    PrioritizedTask<?> task;

                    if (isCoreThread) {
                        // Core threads block indefinitely
                        task = priorityStrategy.takeNext();
                    } else {
                        // Excess threads use timed poll - exit if idle too long
                        var optionalTask = priorityStrategy.pollNext(
                                execConfig.keepAliveSeconds(), TimeUnit.SECONDS);

                        if (optionalTask.isEmpty()) {
                            // Timeout - no work available, scale down
                            log.debug("Worker {} idle for {}s, scaling down",
                                    workerId, execConfig.keepAliveSeconds());
                            break;
                        }
                        task = optionalTask.get();
                    }

                    activeCount.incrementAndGet();
                    try {
                        long startTime = System.currentTimeMillis();
                        log.debug("Worker {} executing task {} (waited {}ms)",
                                workerId, task.getTaskId(), task.getWaitTimeMs());

                        // Execute the task
                        task.run();

                        long duration = System.currentTimeMillis() - startTime;
                        completedCount.incrementAndGet();
                        log.debug("Worker {} completed task {} in {}ms",
                                workerId, task.getTaskId(), duration);

                    } catch (Exception e) {
                        log.error("Worker {} task {} failed: {}",
                                workerId, task.getTaskId(), e.getMessage(), e);
                    } finally {
                        activeCount.decrementAndGet();
                    }

                } catch (InterruptedException e) {
                    if (shutdown.get()) {
                        log.debug("Worker {} interrupted for shutdown", workerId);
                        break;
                    }
                    Thread.currentThread().interrupt();
                }
            }

            // Remove self from workers list if not shutting down
            if (!shutdown.get()) {
                removeWorker(this);
            }

            log.debug("Worker {} stopped", workerId);
        }
    }
}
