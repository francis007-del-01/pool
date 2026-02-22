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
 * @param executors         Executor specifications (each with its own queue)
 * @param priorityTree      Priority tree configuration (list of root nodes)
 * @param priorityStrategy  Priority strategy configuration (FIFO, TIME_BASED, etc.)
 */
public record PoolConfig(
        String name,
        String version,
        List<ExecutorSpec> executors,
        List<PriorityNodeConfig> priorityTree,
        StrategyConfig priorityStrategy
) {
    /**
     * Get executor spec by ID.
     */
    public ExecutorSpec getExecutorById(String executorId) {
        return executors.stream()
                .filter(e -> e.id().equals(executorId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get map of executor ID to executor spec.
     */
    public Map<String, ExecutorSpec> executorsById() {
        return executors.stream()
                .collect(Collectors.toMap(
                        ExecutorSpec::id,
                        Function.identity()
                ));
    }

    /**
     * Get root executor (executor without parent).
     */
    public ExecutorSpec getRootExecutor() {
        return executors.stream()
                .filter(ExecutorSpec::isRoot)
                .findFirst()
                .orElse(null);
    }

    /**
     * Create a minimal configuration for testing.
     */
    public static PoolConfig minimal() {
        return new PoolConfig(
                "test-pool",
                "1.0",
                List.of(ExecutorSpec.root("main", 1000, 5000)),
                List.of(new PriorityNodeConfig(
                        "DEFAULT",
                        "true",
                        null,
                        SortByConfig.fifo(),
                        "main"
                )),
                StrategyConfig.fifo()
        );
    }
}
