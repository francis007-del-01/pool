package com.pool.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for creating TaskContext from JSON payload and context map.
 * Nested JSON objects are flattened using dot notation (e.g., {"x":{"y":"z"}} becomes "x.y" -> "z").
 */
public class TaskContextFactory {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Create a TaskContext from JSON request payload and context map.
     *
     * @param jsonPayload JSON string containing request data (parsed as request variables)
     * @param context     Context map (e.g., headers, metadata) - can be null
     * @return TaskContext with parsed variables
     */
    public static TaskContext create(String jsonPayload, Map<String, String> context) {
        return create(null, jsonPayload, context);
    }

    /**
     * Create a TaskContext from JSON request payload and context map with a specific task ID.
     *
     * @param taskId      Optional task ID (generated if null)
     * @param jsonPayload JSON string containing request data (parsed as request variables)
     * @param context     Context map (e.g., headers, metadata) - can be null
     * @return TaskContext with parsed variables
     */
    public static TaskContext create(String taskId, String jsonPayload, Map<String, String> context) {
        TaskContext.Builder builder = TaskContext.builder();

        if (taskId != null) {
            builder.taskId(taskId);
        }

        // Parse JSON payload and flatten nested objects
        if (jsonPayload != null && !jsonPayload.isBlank()) {
            Map<String, Object> parsed = parseJson(jsonPayload);
            Map<String, Object> flattened = flatten(parsed);
            builder.requestVariables(flattened);
        }

        // Add context variables
        if (context != null) {
            for (Map.Entry<String, String> entry : context.entrySet()) {
                builder.contextVariable(entry.getKey(), entry.getValue());
            }
        }

        return builder.build();
    }

    private static Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload: " + e.getMessage(), e);
        }
    }

    /**
     * Flatten nested maps into dot-notation keys.
     * Example: {"x": {"y": "z"}} becomes {"x.y": "z"}
     */
    private static Map<String, Object> flatten(Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        flattenRecursive("", map, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void flattenRecursive(String prefix, Map<String, Object> map, Map<String, Object> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                // Recursively flatten nested maps
                flattenRecursive(key, (Map<String, Object>) value, result);
            } else if (value instanceof List) {
                // Keep lists as-is (arrays are not flattened)
                result.put(key, value);
            } else {
                // Primitive value
                result.put(key, value);
            }
        }
    }
}
