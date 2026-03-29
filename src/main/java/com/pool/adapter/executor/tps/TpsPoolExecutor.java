package com.pool.adapter.executor.tps;

import com.pool.config.ExecutorHierarchy;
import com.pool.config.PoolConfig;
import com.pool.core.TaskContext;
import com.pool.exception.ConfigurationException;
import com.pool.exception.TaskRejectedException;
import com.pool.policy.EvaluationResult;
import com.pool.policy.PolicyEngine;
import com.pool.priority.PriorityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TPS-based executor with hierarchical executor support.
 * All dependencies are injected — this class is a thin coordinator.
 */
public class TpsPoolExecutor implements com.pool.adapter.executor.PoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TpsPoolExecutor.class);

    private final PoolConfig config;
    private final PolicyEngine policyEngine;
    private final ExecutorHierarchy hierarchy;
    private final TpsGate tpsGate;
    private final TaskQueueManager queueManager;

    private final AtomicInteger submittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);

    public TpsPoolExecutor(PoolConfig config,
                           PolicyEngine policyEngine,
                           ExecutorHierarchy hierarchy,
                           TpsGate tpsGate,
                           TaskQueueManager queueManager) {
        this.config = config;
        this.policyEngine = policyEngine;
        this.hierarchy = hierarchy;
        this.tpsGate = tpsGate;
        this.queueManager = queueManager;

        log.info("TpsPoolExecutor initialized: {} with {} executors, root TPS: {}",
                config.getName(),
                config.getExecutors().size(),
                hierarchy.getTps(hierarchy.getRootIds().iterator().next()));
    }

    @Override
    public void submit(TaskContext context, Runnable task) {
        if (context == null) {
            throw new NullPointerException("Context cannot be null");
        }
        if (task == null) {
            throw new NullPointerException("Task cannot be null");
        }
        if (queueManager.isShutdown()) {
            throw new TaskRejectedException("Executor is shutdown");
        }

        // Evaluate priority and get target executor
        EvaluationResult result = policyEngine.evaluate(context);
        String executorId = result.getMatchedPath().executor();

        // Use default executor if none specified
        if (executorId == null || executorId.isEmpty()) {
            throw new ConfigurationException("Priority tree leaf node has no executor assigned — check your YAML priority-tree configuration");
        }

        PriorityKey priorityKey = result.getPriorityKey();
        String requestId = context.getTaskId();

        submittedCount.incrementAndGet();

        if (tpsGate.tryAcquire(executorId)) {
            queueManager.executeTask(task, requestId, executorId);
            log.debug("Task {} executed immediately (executor: {}, TPS: {}/{})",
                    requestId, executorId,
                    tpsGate.getCurrentTps(executorId),
                    hierarchy.getTps(executorId));
        } else {
            try {
                queueManager.queueTask(task, requestId, executorId, priorityKey, context);
            } catch (TaskRejectedException e) {
                rejectedCount.incrementAndGet();
                throw e;
            }
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
        if (queueManager.isShutdown()) {
            throw new TaskRejectedException("Executor is shutdown");
        }

        FutureTask<T> futureTask = new FutureTask<>(task);

        EvaluationResult result = policyEngine.evaluate(context);
        String executorId = result.getMatchedPath().executor();

        if (executorId == null || executorId.isEmpty()) {
            throw new ConfigurationException("Priority tree leaf node has no executor assigned — check your YAML priority-tree configuration");
        }

        PriorityKey priorityKey = result.getPriorityKey();
        String requestId = context.getTaskId();

        submittedCount.incrementAndGet();

        if (tpsGate.tryAcquire(executorId)) {
            queueManager.executeTask(futureTask, requestId, executorId);
        } else {
            try {
                queueManager.queueTask(futureTask, requestId, executorId, priorityKey, context);
            } catch (TaskRejectedException e) {
                rejectedCount.incrementAndGet();
                throw e;
            }
        }

        return futureTask;
    }

    @Override
    public void shutdown() {
        log.info("Shutting down TpsPoolExecutor: {}", config.getName());
        queueManager.shutdown();
    }

    @Override
    public void shutdownNow() {
        log.info("Shutting down TpsPoolExecutor immediately: {}", config.getName());
        queueManager.shutdownNow();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return queueManager.awaitTermination(timeout, unit);
    }

    @Override
    public boolean isShutdown() {
        return queueManager.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return queueManager.isTerminated();
    }

    @Override
    public int getQueueSize() {
        return queueManager.getTotalQueueSize();
    }

    @Override
    public int getActiveCount() {
        return queueManager.getActiveCount();
    }

    /**
     * Get queue size for a specific executor.
     */
    public int getQueueSize(String executorId) {
        return queueManager.getQueueSize(executorId);
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
                queueManager.getExecutedCount(),
                rejectedCount.get(),
                getQueueSize(),
                queueManager.getActiveCount(),
                hierarchy.getTps(hierarchy.getRootIds().iterator().next()),
                tpsGate.getCurrentTps(hierarchy.getRootIds().iterator().next())
        );
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
