package com.pool.config;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Root configuration for Pool executor.
 *
 * @param name              Pool name identifier
 * @param version           Configuration version
 * @param executors         Executor specifications (each has executor config and queue)
 * @param scheduler         Priority scheduler configuration
 * @param priorityTree      Priority tree configuration (list of root nodes)
 * @param priorityStrategy  Priority strategy configuration (FIFO, TIME_BASED, etc.)
 */
public record PoolConfig(
        String name,
        String version,
        List<ExecutorSpec> executors,
        SchedulerConfig scheduler,
        List<PriorityNodeConfig> priorityTree,
        StrategyConfig priorityStrategy
) {
    /**
     * Get executor spec by queue name.
     */
    public ExecutorSpec getExecutorByQueue(String queueName) {
        return executors.stream()
                .filter(e -> e.queue().name().equals(queueName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get map of queue name to executor spec.
     */
    public Map<String, ExecutorSpec> executorsByQueue() {
        return executors.stream()
                .collect(Collectors.toMap(
                        e -> e.queue().name(),
                        Function.identity()
                ));
    }

    /**
     * Create a minimal configuration for testing.
     */
    public static PoolConfig minimal() {
        return new PoolConfig(
                "test-pool",
                "1.0",
                List.of(ExecutorSpec.defaults("default", 0)),
                SchedulerConfig.minimal(),
                List.of(new PriorityNodeConfig(
                        "DEFAULT",
                        ConditionConfig.alwaysTrue(),
                        null,
                        SortByConfig.fifo(),
                        "default"
                )),
                StrategyConfig.fifo()
        );
    }
}
