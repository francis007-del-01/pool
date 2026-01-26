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
        
        ExecutorConfig executor = parseExecutorConfig(
                (Map<String, Object>) poolConfig.get("executor"));
        
        List<PriorityNodeConfig> priorityTree = parsePriorityTree(
                (List<Map<String, Object>>) poolConfig.get("priority-tree"));

        StrategyConfig strategyConfig = parseStrategyConfig(
                (Map<String, Object>) poolConfig.get("priority-strategy"));

        // Validate configuration
        if (priorityTree == null || priorityTree.isEmpty()) {
            log.warn("No priority-tree configured, using default catch-all");
            priorityTree = List.of(new PriorityNodeConfig(
                    "DEFAULT",
                    ConditionConfig.alwaysTrue(),
                    null,
                    SortByConfig.fifo()
            ));
        }

        PoolConfig config = new PoolConfig(
                name, version, executor, priorityTree, strategyConfig);
        
        log.info("Loaded Pool configuration: {} v{} with {} root nodes, strategy: {}",
                name, version, priorityTree.size(), 
                strategyConfig != null ? strategyConfig.type() : "FIFO (default)");
        
        return config;
    }

    private static ExecutorConfig parseExecutorConfig(Map<String, Object> map) {
        if (map == null) {
            return ExecutorConfig.defaults();
        }
        ExecutorConfig defaults = ExecutorConfig.defaults();
        return new ExecutorConfig(
                getInt(map, "core-pool-size", defaults.corePoolSize()),
                getInt(map, "max-pool-size", defaults.maxPoolSize()),
                getInt(map, "queue-capacity", defaults.queueCapacity()),
                getInt(map, "keep-alive-seconds", defaults.keepAliveSeconds()),
                getString(map, "thread-name-prefix", defaults.threadNamePrefix()),
                getBoolean(map, "allow-core-thread-timeout", defaults.allowCoreThreadTimeout())
        );
    }

    @SuppressWarnings("unchecked")
    private static List<PriorityNodeConfig> parsePriorityTree(List<Map<String, Object>> list) {
        if (list == null) {
            return List.of();
        }
        List<PriorityNodeConfig> nodes = new ArrayList<>();
        for (Map<String, Object> item : list) {
            nodes.add(parsePriorityNode(item));
        }
        return nodes;
    }

    @SuppressWarnings("unchecked")
    private static PriorityNodeConfig parsePriorityNode(Map<String, Object> map) {
        String name = getString(map, "name", "unnamed");
        ConditionConfig condition = parseCondition((Map<String, Object>) map.get("condition"));
        
        List<PriorityNodeConfig> nestedLevels = null;
        List<Map<String, Object>> nestedList = (List<Map<String, Object>>) map.get("nested-levels");
        if (nestedList != null && !nestedList.isEmpty()) {
            nestedLevels = parsePriorityTree(nestedList);
        }
        
        SortByConfig sortBy = parseSortBy((Map<String, Object>) map.get("sort-by"));
        
        return new PriorityNodeConfig(name, condition, nestedLevels, sortBy);
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
