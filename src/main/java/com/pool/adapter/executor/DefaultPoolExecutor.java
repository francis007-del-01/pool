package com.pool.adapter.executor;

import com.pool.config.ExecutorConfig;
import com.pool.config.PoolConfig;
import com.pool.core.TaskContext;
import com.pool.exception.TaskRejectedException;
import com.pool.policy.DefaultPolicyEngine;
import com.pool.policy.PolicyEngine;
import com.pool.scheduler.DefaultPriorityScheduler;
import com.pool.scheduler.PriorityScheduler;
import com.pool.strategy.PriorityStrategy;
import com.pool.strategy.PriorityStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
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
    private final PriorityScheduler<ExecutableTask> priorityScheduler;
    private final List<WorkerThread> workers;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger submittedCount = new AtomicInteger(0);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger workerCount = new AtomicInteger(0);
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
        this.priorityScheduler = new DefaultPriorityScheduler<>(policyEngine, priorityStrategy);

        // Create worker threads
        this.workers = new ArrayList<>();

        // Start core worker threads
        for (int i = 0; i < execConfig.corePoolSize(); i++) {
            startWorker(i + 1, true);
        }

        log.info("PoolExecutor initialized: {} (strategy={}, core={}, max={}, queueCapacity={})",
                config.name(), priorityStrategy.getName(),
                execConfig.corePoolSize(), execConfig.maxPoolSize(),
                execConfig.queueCapacity());
    }

    private void startWorker(int workerId, boolean isCoreThread) {
        WorkerThread worker = new WorkerThread(
                workerId,
                isCoreThread,
                execConfig,
                priorityScheduler,
                shutdown,
                activeCount,
                completedCount,
                this::removeWorker,
                log
        );
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

        FutureTask<Void> futureTask = new FutureTask<>(task, null);
        ExecutableTask executableTask = new ExecutableTask(futureTask, context.getTaskId());

        if (!priorityScheduler.submit(context, executableTask)) {
            rejectedCount.incrementAndGet();
            throw new TaskRejectedException("Queue is full, task rejected: " + context.getTaskId());
        }

        submittedCount.incrementAndGet();
        log.debug("Task {} submitted (queue size: {})",
                context.getTaskId(), priorityScheduler.size());

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

        FutureTask<T> futureTask = new FutureTask<>(task);
        ExecutableTask executableTask = new ExecutableTask(futureTask, context.getTaskId());

        if (!priorityScheduler.submit(context, executableTask)) {
            rejectedCount.incrementAndGet();
            throw new TaskRejectedException("Queue is full, task rejected: " + context.getTaskId());
        }

        submittedCount.incrementAndGet();
        log.debug("Task {} submitted (queue size: {})",
                context.getTaskId(), priorityScheduler.size());

        // Scale up workers if needed
        maybeScaleUp();

        return futureTask;
    }

    /**
     * Scale up workers if queue has items and we haven't hit max.
     */
    private void maybeScaleUp() {
        int currentWorkers = workerCount.get();
        int queueSize = priorityScheduler.size();

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
        priorityScheduler.shutdown();

        // Interrupt workers waiting on queue
        for (WorkerThread worker : workers) {
            worker.interrupt();
        }
    }

    @Override
    public void shutdownNow() {
        log.info("Shutting down PoolExecutor immediately: {}", config.name());
        shutdown.set(true);
        priorityScheduler.shutdown();

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
        return priorityScheduler.size();
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
     * Core threads block indefinitely unless allow-core-thread-timeout is enabled.
     */
}
