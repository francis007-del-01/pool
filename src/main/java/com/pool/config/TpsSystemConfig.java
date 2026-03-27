package com.pool.config;

import com.pool.adapter.executor.tps.TaskQueueManager;
import com.pool.adapter.executor.tps.TpsGate;
import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.core.TpsCounter;
import com.pool.policy.PolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Centralized configuration for the TPS executor system.
 * Creates and wires all TPS-related beans:
 * ExecutorHierarchy, TpsGate (with TpsCounters), TaskQueueManager, TpsPoolExecutor.
 *
 * All cross-component wiring (e.g., reset callbacks) is handled here,
 * keeping individual classes decoupled from each other's internals.
 */
@Configuration
public class TpsSystemConfig {

    private static final Logger log = LoggerFactory.getLogger(TpsSystemConfig.class);

    private static final long DEFAULT_WINDOW_SIZE_MS = 1000;

    private final ExecutorHierarchy hierarchy;
    private final Map<String, ReentrantLock> capacityLocks = new ConcurrentHashMap<>();
    private final Map<String, Condition> capacityConditions = new ConcurrentHashMap<>();

    public TpsSystemConfig(PoolConfig config) {
        this.hierarchy = new ExecutorHierarchy(config);
        for (String executorId : hierarchy.getAllExecutorIds()) {
            ReentrantLock lock = new ReentrantLock();
            capacityLocks.put(executorId, lock);
            capacityConditions.put(executorId, lock.newCondition());
        }
        log.info("TpsSystemConfig initialized: {} executors, locks and conditions ready",
                hierarchy.getAllExecutorIds().size());
    }

    @Bean
    public ExecutorHierarchy executorHierarchy() {
        return hierarchy;
    }

    @Bean
    public TpsGate tpsGate(ExecutorHierarchy hierarchy) {
        ConcurrentHashMap<String, TpsCounter> counters = new ConcurrentHashMap<>();

        for (String executorId : hierarchy.getAllExecutorIds()) {
            TpsCounter counter = new TpsCounter(DEFAULT_WINDOW_SIZE_MS);
            final String execId = executorId;
            counter.setOnReset(() -> signalCapacity(execId));
            counters.put(executorId, counter);
        }

        return new TpsGate(hierarchy, counters, DEFAULT_WINDOW_SIZE_MS);
    }

    @Bean
    public TaskQueueManager taskQueueManager(ExecutorHierarchy hierarchy, TpsGate tpsGate) {
        Map<String, com.pool.strategy.PriorityStrategy<TaskQueueManager.QueuedTask>> executorStrategies = new ConcurrentHashMap<>();

        for (String executorId : hierarchy.getAllExecutorIds()) {
            int queueCapacity = hierarchy.getQueueCapacity(executorId);
            executorStrategies.put(executorId,
                    com.pool.strategy.PriorityStrategyFactory.createDefault(queueCapacity));
        }

        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("tps-pool-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        log.info("TPS system wired: {} executors", hierarchy.getAllExecutorIds().size());
        return new TaskQueueManager(hierarchy, tpsGate, executorStrategies,
                capacityLocks, capacityConditions, threadPool);
    }

    private void signalCapacity(String executorId) {
        ReentrantLock lock = capacityLocks.get(executorId);
        if (lock != null) {
            lock.lock();
            try {
                Condition condition = capacityConditions.get(executorId);
                if (condition != null) {
                    condition.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    @Bean
    public TpsPoolExecutor tpsPoolExecutor(PoolConfig config,
                                           PolicyEngine policyEngine,
                                           ExecutorHierarchy hierarchy,
                                           TpsGate tpsGate,
                                           TaskQueueManager queueManager) {
        return new TpsPoolExecutor(config, policyEngine, hierarchy, tpsGate, queueManager);
    }
}
