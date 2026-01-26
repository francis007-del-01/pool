package com.pool.adapter.executor;

import com.pool.config.ExecutorConfig;
import com.pool.scheduler.PriorityScheduler;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Worker thread that pulls tasks from the priority scheduler.
 */
final class WorkerThread extends Thread {

    private final int workerId;
    private final boolean isCoreThread;
    private final ExecutorConfig execConfig;
    private final PriorityScheduler<ExecutableTask> priorityScheduler;
    private final AtomicBoolean shutdown;
    private final AtomicInteger activeCount;
    private final AtomicInteger completedCount;
    private final Consumer<WorkerThread> onRemove;
    private final Logger log;

    WorkerThread(
            int workerId,
            boolean isCoreThread,
            ExecutorConfig execConfig,
            PriorityScheduler<ExecutableTask> priorityScheduler,
            AtomicBoolean shutdown,
            AtomicInteger activeCount,
            AtomicInteger completedCount,
            Consumer<WorkerThread> onRemove,
            Logger log
    ) {
        super(execConfig.threadNamePrefix() + workerId);
        this.workerId = workerId;
        this.isCoreThread = isCoreThread;
        this.execConfig = execConfig;
        this.priorityScheduler = priorityScheduler;
        this.shutdown = shutdown;
        this.activeCount = activeCount;
        this.completedCount = completedCount;
        this.onRemove = onRemove;
        this.log = log;
        setDaemon(false);
    }

    @Override
    public void run() {
        log.debug("Worker {} started (core={})", workerId, isCoreThread);

        while (!shutdown.get()) {
            try {
                ExecutableTask task;

                boolean allowCoreTimeout = execConfig.allowCoreThreadTimeout();
                if (isCoreThread && !allowCoreTimeout) {
                    // Core threads block indefinitely
                    task = priorityScheduler.getNext();
                } else {
                    // Timed poll - exit if idle too long
                    Optional<ExecutableTask> optionalTask = priorityScheduler.getNext(
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
                    log.debug("Worker {} executing task {}", workerId, task.getTaskId());

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
            onRemove.accept(this);
        }

        log.debug("Worker {} stopped", workerId);
    }
}
