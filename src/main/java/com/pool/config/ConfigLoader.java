package com.pool.config;

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
        
        // Parse adapters.executors - each executor has its own queue
        Map<String, Object> adaptersConfig = (Map<String, Object>) poolConfig.get("adapters");
        List<ExecutorSpec> executorSpecs = parseExecutorSpecs(adaptersConfig);
        
        List<PriorityNodeConfig> priorityTree = parsePriorityTree(
                (List<Map<String, Object>>) poolConfig.get("priority-tree"));

        StrategyConfig strategyConfig = parseStrategyConfig(
                (Map<String, Object>) poolConfig.get("priority-strategy"));

        // Validate configuration
        if (priorityTree == null || priorityTree.isEmpty()) {
            log.warn("No priority-tree configured, using default catch-all");
            priorityTree = List.of(new PriorityNodeConfig(
                    "DEFAULT",
                    "true",  // always true expression
                    null,
                    SortByConfig.fifo(),
                    "main"  // default executor
            ));
        }

        // Validate executor hierarchy (done in ExecutorHierarchy constructor)

        PoolConfig config = new PoolConfig(
                name, version, executorSpecs, priorityTree, strategyConfig);
        
        log.info("Loaded Pool configuration: {} v{} with {} executors, {} root nodes, strategy: {}",
                name, version, executorSpecs.size(), priorityTree.size(),
                strategyConfig != null ? strategyConfig.type() : "FIFO (default)");
        
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
            
            // Parse executor ID (required)
            String id = getString(execMap, "id", null);
            if (id == null || id.isBlank()) {
                id = "executor-" + i;
            }
            
            // Parse parent (null for root)
            String parent = getString(execMap, "parent", null);
            
            // Parse TPS limit (0 = unbounded)
            int tps = getInt(execMap, "tps", 0);
            
            // Parse queue capacity (only for root executor)
            int queueCapacity = getInt(execMap, "queue_capacity", 0);
            if (queueCapacity == 0) {
                queueCapacity = getInt(execMap, "queue-capacity", 0);
            }
            
            // Parse identifier field for TPS counting (e.g., "$req.ipAddress")
            String identifierField = getString(execMap, "identifier_field", null);
            if (identifierField == null || identifierField.isBlank()) {
                identifierField = getString(execMap, "identifier-field", null);
            }

            specs.add(new ExecutorSpec(id, parent, tps, queueCapacity, identifierField));

            log.debug("Parsed executor spec: id={}, parent={}, tps={}, queueCapacity={}, identifierField={}",
                    id, parent, tps, queueCapacity, identifierField);
        }
        
        return specs;
    }

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
        String condition = parseConditionExpression(map);
        
        List<PriorityNodeConfig> nestedLevels = null;
        List<Map<String, Object>> nestedList = (List<Map<String, Object>>) map.get("nested-levels");
        if (nestedList != null && !nestedList.isEmpty()) {
            nestedLevels = parsePriorityTree(nestedList);
        }
        
        SortByConfig sortBy = parseSortBy((Map<String, Object>) map.get("sort-by"));
        String executor = getString(map, "executor", null);
        return new PriorityNodeConfig(name, condition, nestedLevels, sortBy, executor);
    }

    /**
     * Parse condition expression - expects a string expression.
     */
    private static String parseConditionExpression(Map<String, Object> map) {
        Object conditionObj = map.get("condition");
        if (conditionObj instanceof String conditionStr) {
            return conditionStr;
        }
        return "true"; // Default: always true
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
