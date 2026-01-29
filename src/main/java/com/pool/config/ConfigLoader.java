package com.pool.config;

import com.pool.condition.ConditionType;
import com.pool.exception.ConfigurationException;
import com.pool.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads Pool configuration from YAML files.
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    /**
     * Load configuration from a path.
     * Supports classpath: prefix for classpath resources.
     *
     * @param path Path to the configuration file
     * @return Loaded configuration
     */
    public static PoolConfig load(String path) {
        log.info("Loading Pool configuration from: {}", path);
        
        try {
            Resource resource = getResource(path);
            try (InputStream inputStream = resource.getInputStream()) {
                return parseYaml(inputStream);
            }
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load configuration from: " + path, e);
        }
    }

    private static Resource getResource(String path) {
        if (path.startsWith("classpath:")) {
            String resourcePath = path.substring("classpath:".length());
            return new ClassPathResource(resourcePath);
        }
        return new FileSystemResource(path);
    }

    @SuppressWarnings("unchecked")
    private static PoolConfig parseYaml(InputStream inputStream) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(inputStream);
        
        if (root == null) {
            throw new ConfigurationException("Configuration file is empty");
        }

        // Get the pool section (could be at root or under 'pool' key)
        Map<String, Object> poolConfig = root.containsKey("pool") 
                ? (Map<String, Object>) root.get("pool")
                : root;

        String name = getString(poolConfig, "name", "default-pool");
        String version = getString(poolConfig, "version", "1.0");
        SyntaxUsed syntaxUsed = parseSyntaxUsed(poolConfig);
        
        // Parse adapters.executors - each executor targets a named queue
        Map<String, Object> adaptersConfig = (Map<String, Object>) poolConfig.get("adapters");
        List<ExecutorSpec> executorSpecs = parseExecutorSpecs(adaptersConfig);
        
        // Parse scheduler config (queues are defined here)
        Map<String, Object> schedulerMap = (Map<String, Object>) poolConfig.get("scheduler");
        SchedulerConfig scheduler = parseSchedulerConfig(schedulerMap);
        
        List<PriorityNodeConfig> priorityTree = parsePriorityTree(
                (List<Map<String, Object>>) poolConfig.get("priority-tree"),
                syntaxUsed);

        StrategyConfig strategyConfig = parseStrategyConfig(
                (Map<String, Object>) poolConfig.get("priority-strategy"));

        // Validate configuration
        if (priorityTree == null || priorityTree.isEmpty()) {
            log.warn("No priority-tree configured, using default catch-all");
            String defaultQueue = scheduler.queues().isEmpty() ? "default" : scheduler.queues().get(0).name();
            priorityTree = List.of(new PriorityNodeConfig(
                    "DEFAULT",
                    ConditionConfig.alwaysTrue(),
                    null,
                    SortByConfig.fifo(),
                    defaultQueue
            ));
        }

        // Validate executor -> queue mapping
        if (executorSpecs != null && !executorSpecs.isEmpty()) {
            for (ExecutorSpec spec : executorSpecs) {
                QueueConfig queue = scheduler.getQueue(spec.queueName());
                if (queue == null) {
                    throw new ConfigurationException(
                            "Executor targets unknown queue '" + spec.queueName()
                                    + "'. Define it under scheduler.queues.");
                }
            }
        }

        PoolConfig config = new PoolConfig(
                name, version, executorSpecs, scheduler, priorityTree, syntaxUsed, strategyConfig);
        
        log.info("Loaded Pool configuration: {} v{} with {} executors, {} queues, {} root nodes, syntax: {}, strategy: {}",
                name, version, executorSpecs.size(), scheduler.queues().size(), priorityTree.size(),
                syntaxUsed, strategyConfig != null ? strategyConfig.type() : "FIFO (default)");
        
        return config;
    }

    @SuppressWarnings("unchecked")
    private static List<ExecutorSpec> parseExecutorSpecs(Map<String, Object> adaptersConfig) {
        if (adaptersConfig == null) {
            return List.of();
        }
        
        List<Map<String, Object>> executorsList = 
                (List<Map<String, Object>>) adaptersConfig.get("executors");
        if (executorsList == null || executorsList.isEmpty()) {
            return List.of();
        }
        
        List<ExecutorSpec> specs = new ArrayList<>();
        for (int i = 0; i < executorsList.size(); i++) {
            Map<String, Object> execMap = executorsList.get(i);
            
            // Parse queue name (string preferred). If a map is provided, use its name.
            Object queueObj = execMap.get("queue");
            String queueName = null;
            if (queueObj instanceof Map<?, ?> queueMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> queueMapTyped = (Map<String, Object>) queueMap;
                queueName = getString(queueMapTyped, "name", null);
                log.warn("Executor queue should be a name string. Found object for executor {}.", i);
            } else if (queueObj != null) {
                queueName = queueObj.toString();
            }
            if (queueName == null || queueName.isBlank()) {
                queueName = getString(execMap, "queue-name", "queue-" + i);
            }
            
            // Parse executor config
            int corePoolSize = getInt(execMap, "core-pool-size", 10);
            int maxPoolSize = getInt(execMap, "max-pool-size", 50);
            int keepAliveSeconds = getInt(execMap, "keep-alive-seconds", 60);
            String threadNamePrefix = getString(execMap, "thread-name-prefix", queueName + "-worker-");
            boolean allowCoreThreadTimeout = getBoolean(execMap, "allow-core-thread-timeout", true);
            
            specs.add(new ExecutorSpec(
                    queueName,
                    corePoolSize,
                    maxPoolSize,
                    keepAliveSeconds,
                    threadNamePrefix,
                    allowCoreThreadTimeout
            ));
            
            log.debug("Parsed executor spec: queueName={}, corePool={}, maxPool={}",
                    queueName, corePoolSize, maxPoolSize);
        }
        
        return specs;
    }

    /**
     * Parse scheduler config.
     * Queues can come from:
     * 1. adapters.executors (each executor has a queue) - for executor adapter use
     * 2. scheduler.queues - for standalone scheduler use (no adapter)
     * If both are present, executor queues take precedence.
     */
    @SuppressWarnings("unchecked")
    private static SchedulerConfig parseSchedulerConfig(Map<String, Object> schedulerMap) {
        if (schedulerMap == null) {
            return SchedulerConfig.defaults();
        }
        
        // Check for queues list
        List<Map<String, Object>> queuesList = (List<Map<String, Object>>) schedulerMap.get("queues");
        if (queuesList != null && !queuesList.isEmpty()) {
            List<QueueConfig> queues = new ArrayList<>();
            for (int i = 0; i < queuesList.size(); i++) {
                Map<String, Object> queueMap = queuesList.get(i);
                String name = getString(queueMap, "name", "queue-" + i);
                int index = getInt(queueMap, "index", i);
                int capacity = getInt(queueMap, "capacity", 1000);
                queues.add(new QueueConfig(name, index, capacity));
                log.debug("Parsed scheduler queue: name={}, index={}, capacity={}", name, index, capacity);
            }
            return new SchedulerConfig(queues);
        }
        
        // Fallback: single queue with queue-capacity
        int queueCapacity = getInt(schedulerMap, "queue-capacity", 1000);
        return new SchedulerConfig(List.of(new QueueConfig("default", 0, queueCapacity)));
    }

    @SuppressWarnings("unchecked")
    private static List<PriorityNodeConfig> parsePriorityTree(List<Map<String, Object>> list,
                                                              SyntaxUsed syntaxUsed) {
        if (list == null) {
            return List.of();
        }
        List<PriorityNodeConfig> nodes = new ArrayList<>();
        for (Map<String, Object> item : list) {
            nodes.add(parsePriorityNode(item, syntaxUsed));
        }
        return nodes;
    }

    @SuppressWarnings("unchecked")
    private static PriorityNodeConfig parsePriorityNode(Map<String, Object> map, SyntaxUsed syntaxUsed) {
        String name = getString(map, "name", "unnamed");
        ConditionConfig condition = parseConditionBySyntax(map, name, syntaxUsed);
        
        List<PriorityNodeConfig> nestedLevels = null;
        List<Map<String, Object>> nestedList = (List<Map<String, Object>>) map.get("nested-levels");
        if (nestedList != null && !nestedList.isEmpty()) {
            if (syntaxUsed == SyntaxUsed.CONDITION_EXPR) {
                throw new ConfigurationException("Priority node '" + name
                        + "' has nested-levels but syntax-used is CONDITION_EXPR. "
                        + "Expression mode uses flat sequential evaluation - remove nested-levels.");
            }
            nestedLevels = parsePriorityTree(nestedList, syntaxUsed);
        }
        
        SortByConfig sortBy = parseSortBy((Map<String, Object>) map.get("sort-by"));
        String queue = getString(map, "queue", null);
        
        return new PriorityNodeConfig(name, condition, nestedLevels, sortBy, queue);
    }

    @SuppressWarnings("unchecked")
    private static ConditionConfig parseConditionBySyntax(Map<String, Object> map,
                                                          String nodeName,
                                                          SyntaxUsed syntaxUsed) {
        String conditionExpr = getString(map, "condition-expr", null);
        if (conditionExpr == null) {
            conditionExpr = getString(map, "conditionExpr", null);
        }
        Map<String, Object> conditionMap = (Map<String, Object>) map.get("condition");

        if (syntaxUsed == SyntaxUsed.CONDITION_TREE) {
            if (conditionExpr != null) {
                throw new ConfigurationException("Priority node '" + nodeName
                        + "' uses condition-expr but syntax-used is CONDITION_TREE");
            }
            return parseCondition(conditionMap);
        }

        if (syntaxUsed == SyntaxUsed.CONDITION_EXPR) {
            if (conditionMap != null) {
                throw new ConfigurationException("Priority node '" + nodeName
                        + "' uses condition but syntax-used is CONDITION_EXPR");
            }
            return ConditionExpressionParser.parse(conditionExpr);
        }

        return ConditionConfig.alwaysTrue();
    }

    private static SyntaxUsed parseSyntaxUsed(Map<String, Object> poolConfig) {
        String syntax = getString(poolConfig, "syntax-used", null);
        if (syntax == null) {
            syntax = getString(poolConfig, "syntaxUsed", null);
        }
        if (syntax == null || syntax.isBlank()) {
            return SyntaxUsed.CONDITION_TREE;
        }
        return SyntaxUsed.valueOf(syntax.toUpperCase().replace("-", "_"));
    }

    @SuppressWarnings("unchecked")
    private static ConditionConfig parseCondition(Map<String, Object> map) {
        if (map == null) {
            return ConditionConfig.alwaysTrue();
        }
        
        String typeStr = getString(map, "type", "ALWAYS_TRUE");
        ConditionType type = ConditionType.valueOf(typeStr.toUpperCase().replace("-", "_"));
        
        String field = getString(map, "field", null);
        Object value = map.get("value");
        Object value2 = map.get("value2");
        List<Object> values = (List<Object>) map.get("values");
        String pattern = getString(map, "pattern", null);
        
        List<ConditionConfig> nestedConditions = null;
        List<Map<String, Object>> conditionsList = (List<Map<String, Object>>) map.get("conditions");
        if (conditionsList != null) {
            nestedConditions = new ArrayList<>();
            for (Map<String, Object> c : conditionsList) {
                nestedConditions.add(parseCondition(c));
            }
        }
        
        return new ConditionConfig(type, field, value, value2, values, pattern, nestedConditions);
    }

    private static SortByConfig parseSortBy(Map<String, Object> map) {
        if (map == null) {
            return null; // Will default to FIFO at leaf nodes
        }
        String field = getString(map, "field", "$sys.submittedAt");
        String dirStr = getString(map, "direction", "ASC");
        SortDirection direction = SortDirection.valueOf(dirStr.toUpperCase());
        return new SortByConfig(field, direction);
    }

    @SuppressWarnings("unchecked")
    private static StrategyConfig parseStrategyConfig(Map<String, Object> map) {
        if (map == null) {
            return StrategyConfig.fifo(); // Default to FIFO
        }

        String typeStr = getString(map, "type", "FIFO");
        StrategyType type = StrategyType.valueOf(typeStr.toUpperCase().replace("-", "_"));

        StrategyConfig.TimeBasedConfig timeBasedConfig = null;
        StrategyConfig.BucketBasedConfig bucketBasedConfig = null;

        // Parse TIME_BASED config (for future use)
        Map<String, Object> timeBasedMap = (Map<String, Object>) map.get("time-based");
        if (timeBasedMap != null) {
            timeBasedConfig = new StrategyConfig.TimeBasedConfig(
                    getLong(timeBasedMap, "aging-interval-ms", 5000),
                    getLong(timeBasedMap, "boost-per-interval", 1),
                    getLong(timeBasedMap, "max-boost", 100)
            );
        }

        // Parse BUCKET_BASED config (for future use)
        Map<String, Object> bucketBasedMap = (Map<String, Object>) map.get("bucket-based");
        if (bucketBasedMap != null) {
            int bucketCount = getInt(bucketBasedMap, "bucket-count", 4);
            List<Integer> capacitiesList = (List<Integer>) bucketBasedMap.get("bucket-capacities");
            int[] bucketCapacities = capacitiesList != null
                    ? capacitiesList.stream().mapToInt(Integer::intValue).toArray()
                    : new int[]{100, 500, 2000, Integer.MAX_VALUE};
            bucketBasedConfig = new StrategyConfig.BucketBasedConfig(
                    bucketCount,
                    bucketCapacities,
                    getLong(bucketBasedMap, "promotion-interval-ms", 10000)
            );
        }

        return new StrategyConfig(type, timeBasedConfig, bucketBasedConfig);
    }

    // Helper methods

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    private static long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }
}
