package com.pool.config;

import java.util.List;

/**
 * Root configuration for Pool executor.
 *
 * @param name              Pool name identifier
 * @param version           Configuration version
 * @param executor          Thread pool executor configuration (adapter)
 * @param scheduler         Priority scheduler configuration
 * @param priorityTree      Priority tree configuration (list of root nodes)
 * @param priorityStrategy  Priority strategy configuration (FIFO, TIME_BASED, etc.)
 */
public record PoolConfig(
        String name,
        String version,
        ExecutorConfig executor,
        SchedulerConfig scheduler,
        List<PriorityNodeConfig> priorityTree,
        StrategyConfig priorityStrategy
) {
    /**
     * Create a minimal configuration for testing.
     */
    public static PoolConfig minimal() {
        return new PoolConfig(
                "test-pool",
                "1.0",
                ExecutorConfig.minimal(),
                SchedulerConfig.minimal(),
                List.of(new PriorityNodeConfig(
                        "DEFAULT",
                        ConditionConfig.alwaysTrue(),
                        null,
                        SortByConfig.fifo()
                )),
                StrategyConfig.fifo()
        );
    }
}
