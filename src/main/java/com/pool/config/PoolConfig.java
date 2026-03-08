package com.pool.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Root configuration for Pool executor.
 * Bound directly from application YAML under the "pool" prefix.
 */
@Data
@ConfigurationProperties(prefix = "pool")
public class PoolConfig {

    /**
     * Pool name identifier.
     */
    @NotBlank
    private String name = "default-pool";

    /**
     * Configuration version.
     */
    private String version = "1.0";

    /**
     * Adapters configuration (contains executor specs).
     */
    @Valid
    private AdaptersConfig adapters = new AdaptersConfig();

    /**
     * Priority tree configuration (list of root nodes).
     */
    @Valid
    private List<PriorityNodeConfig> priorityTree = new ArrayList<>();

    /**
     * Priority strategy configuration (FIFO, TIME_BASED, etc.).
     */
    @Valid
    private StrategyConfig priorityStrategy = StrategyConfig.fifo();

    /**
     * Get executor specs (convenience accessor).
     */
    public List<ExecutorSpec> getExecutors() {
        return adapters != null ? adapters.getExecutors() : List.of();
    }

    /**
     * Get executor spec by ID.
     */
    public ExecutorSpec getExecutorById(String executorId) {
        return getExecutors().stream()
                .filter(e -> e.getId().equals(executorId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get map of executor ID to executor spec.
     */
    public Map<String, ExecutorSpec> executorsById() {
        return getExecutors().stream()
                .collect(Collectors.toMap(
                        ExecutorSpec::getId,
                        Function.identity()
                ));
    }

    /**
     * Get root executor (executor without parent).
     */
    public ExecutorSpec getRootExecutor() {
        return getExecutors().stream()
                .filter(ExecutorSpec::isRoot)
                .findFirst()
                .orElse(null);
    }

    /**
     * Create a minimal configuration for testing.
     */
    public static PoolConfig minimal() {
        PoolConfig config = new PoolConfig();
        config.setName("test-pool");
        config.setVersion("1.0");

        AdaptersConfig adapters = new AdaptersConfig();
        adapters.setExecutors(new ArrayList<>(List.of(ExecutorSpec.root("main", 1000, 5000))));
        config.setAdapters(adapters);

        PriorityNodeConfig defaultNode = new PriorityNodeConfig();
        defaultNode.setName("DEFAULT");
        defaultNode.setCondition("true");
        defaultNode.setSortBy(SortByConfig.fifo());
        defaultNode.setExecutor("main");
        config.setPriorityTree(new ArrayList<>(List.of(defaultNode)));

        config.setPriorityStrategy(StrategyConfig.fifo());
        return config;
    }
}
