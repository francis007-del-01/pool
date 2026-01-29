package com.pool.adapter.executor;

import com.pool.config.ExecutorSpec;
import com.pool.scheduler.PriorityScheduler;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Worker thread that pulls tasks from a specific queue in the priority scheduler.
 */
final class WorkerThread extends Thread {

    private final int workerId;
    private final String queueName;
    private final ExecutorSpec execSpec;
    private final PriorityScheduler<ExecutableTask> priorityScheduler;
    private final AtomicBoolean shutdown;
    private final AtomicInteger activeCount;
    private final AtomicInteger completedCount;
    private final Consumer<WorkerThread> onRemove;
    private final Logger log;

    WorkerThread(
            int workerId,
            String queueName,
            ExecutorSpec execSpec,
            PriorityScheduler<ExecutableTask> priorityScheduler,
            AtomicBoolean shutdown,
            AtomicInteger activeCount,
            AtomicInteger completedCount,
            Consumer<WorkerThread> onRemove,
            Logger log
    ) {
        super(execSpec.threadNamePrefix() + workerId);
        this.workerId = workerId;
        this.queueName = queueName;
        this.execSpec = execSpec;
        this.priorityScheduler = priorityScheduler;
        this.shutdown = shutdown;
        this.activeCount = activeCount;
        this.completedCount = completedCount;
        this.onRemove = onRemove;
        this.log = log;
        setDaemon(false);
    }

    public String getQueueName() {
        return queueName;
    }

    @Override
    public void run() {
        log.debug("Worker {} started for queue '{}'", workerId, queueName);

        while (!shutdown.get()) {
            try {
                ExecutableTask task;
                if (execSpec.allowCoreThreadTimeout()) {
                    // Timed poll - exit if idle too long
                    var optionalTask = priorityScheduler.getNext(
                            queueName, execSpec.keepAliveSeconds(), TimeUnit.SECONDS);
                    if (optionalTask.isEmpty()) {
                        log.debug("Worker {} idle for {}s on queue '{}', scaling down",
                                workerId, execSpec.keepAliveSeconds(), queueName);
                        break;
                    }
                    task = optionalTask.get();
                } else {
                    // Workers block indefinitely on their queue
                    task = priorityScheduler.getNext(queueName);
                }

                activeCount.incrementAndGet();
                try {
                    long startTime = System.currentTimeMillis();
                    log.debug("Worker {} executing task {} from queue '{}'", 
                            workerId, task.getTaskId(), queueName);

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
