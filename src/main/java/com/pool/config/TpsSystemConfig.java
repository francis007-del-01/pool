package com.pool.config;

import com.pool.adapter.executor.tps.TaskQueueManager;
import com.pool.adapter.executor.tps.TpsGate;
import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.core.SlidingWindowCounter;
import com.pool.policy.PolicyEngine;
import com.pool.variable.DefaultVariableResolver;
import com.pool.variable.VariableResolver;
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
 * ExecutorHierarchy, TpsGate, TaskQueueManager, TpsPoolExecutor.
 *
 * All cross-component wiring (e.g., eviction callbacks) is handled here,
 * keeping individual classes decoupled from each other's internals.
 */
@Configuration
public class TpsSystemConfig {

    private static final Logger log = LoggerFactory.getLogger(TpsSystemConfig.class);

    private static final long DEFAULT_WINDOW_SIZE_MS = 1000;
    private static final long DEFAULT_ADMITTED_TTL_MS = 5 * 60 * 1000L;

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
    public VariableResolver variableResolver() {
        return new DefaultVariableResolver();
    }

    @Bean
    public ExecutorHierarchy executorHierarchy() {
        return hierarchy;
    }

    @Bean
    public TpsGate tpsGate(ExecutorHierarchy hierarchy, VariableResolver variableResolver) {
        ConcurrentHashMap<String, SlidingWindowCounter> counters = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, SlidingWindowCounter> admittedIds = new ConcurrentHashMap<>();

        for (String executorId : hierarchy.getAllExecutorIds()) {
            SlidingWindowCounter counter = new SlidingWindowCounter(DEFAULT_WINDOW_SIZE_MS);
            final String execId = executorId;
            counter.setOnEviction(() -> signalCapacity(execId));
            counters.put(executorId, counter);

            admittedIds.put(executorId, new SlidingWindowCounter(DEFAULT_ADMITTED_TTL_MS));
        }

        return new TpsGate(hierarchy, counters, admittedIds, variableResolver,
                DEFAULT_WINDOW_SIZE_MS, DEFAULT_ADMITTED_TTL_MS);
    }

    @Bean
    public TaskQueueManager taskQueueManager(ExecutorHierarchy hierarchy, TpsGate tpsGate) {
        Map<String, com.pool.strategy.PriorityStrategy<TaskQueueManager.QueuedTask>> executorStrategies = new ConcurrentHashMap<>();

        for (String executorId : hierarchy.getAllExecutorIds()) {
            int queueCapacity = hierarchy.getQueueCapacity(executorId);
            if (queueCapacity <= 0) {
                queueCapacity = Integer.MAX_VALUE;
            }
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
