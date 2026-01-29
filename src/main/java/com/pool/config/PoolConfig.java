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
 * @param executors         Executor specifications (each targets a queue by name)
 * @param scheduler         Priority scheduler configuration
 * @param priorityTree      Priority tree configuration (list of root nodes)
 * @param syntaxUsed        Condition syntax to use (tree or expression)
 * @param priorityStrategy  Priority strategy configuration (FIFO, TIME_BASED, etc.)
 */
public record PoolConfig(
        String name,
        String version,
        List<ExecutorSpec> executors,
        SchedulerConfig scheduler,
        List<PriorityNodeConfig> priorityTree,
        SyntaxUsed syntaxUsed,
        StrategyConfig priorityStrategy
) {
    /**
     * Get executor spec by queue name.
     */
    public ExecutorSpec getExecutorByQueue(String queueName) {
        return executors.stream()
                .filter(e -> e.queueName().equals(queueName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get map of queue name to executor spec.
     */
    public Map<String, ExecutorSpec> executorsByQueue() {
        return executors.stream()
                .collect(Collectors.toMap(
                        ExecutorSpec::queueName,
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
                List.of(ExecutorSpec.defaults("default")),
                SchedulerConfig.minimal(),
                List.of(new PriorityNodeConfig(
                        "DEFAULT",
                        ConditionConfig.alwaysTrue(),
                        null,
                        SortByConfig.fifo(),
                        "default"
                )),
                SyntaxUsed.CONDITION_TREE,
                StrategyConfig.fifo()
        );
    }
}
