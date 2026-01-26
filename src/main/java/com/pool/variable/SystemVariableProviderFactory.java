package com.pool.variable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Factory that provides all registered system variable providers.
 * Use this to get all system variables and populate them in TaskContext.
 */
public class SystemVariableProviderFactory {

    private static final List<SystemVariableProvider> PROVIDERS = new ArrayList<>();

    static {
        // Register all built-in system variable providers
        PROVIDERS.add(new TaskIdProvider());
        PROVIDERS.add(new SubmittedAtProvider());
        PROVIDERS.add(new TimeNowProvider());
        PROVIDERS.add(new CorrelationIdProvider());
    }

    /**
     * Get all registered system variable providers.
     *
     * @return Unmodifiable list of providers
     */
    public static List<SystemVariableProvider> getProviders() {
        return Collections.unmodifiableList(PROVIDERS);
    }

    /**
     * Compute all system variables for the given context.
     *
     * @param context The context containing builder inputs
     * @return Map of variable name to value (excludes null values)
     */
    public static Map<String, Object> computeAll(SystemVariableContext context) {
        Map<String, Object> result = new HashMap<>();
        for (SystemVariableProvider provider : PROVIDERS) {
            Object value = provider.getValue(context);
            if (value != null) {
                result.put(provider.getName(), value);
            }
        }
        return result;
    }

    // Built-in providers as static inner classes

    private static class TaskIdProvider implements SystemVariableProvider {
        @Override
        public String getName() {
            return "taskId";
        }

        @Override
        public Object getValue(SystemVariableContext context) {
            return context.getTaskIdFromBuilder()
                    .orElseGet(() -> UUID.randomUUID().toString());
        }
    }

    private static class SubmittedAtProvider implements SystemVariableProvider {
        @Override
        public String getName() {
            return "submittedAt";
        }

        @Override
        public Object getValue(SystemVariableContext context) {
            return System.currentTimeMillis();
        }
    }

    private static class TimeNowProvider implements SystemVariableProvider {
        @Override
        public String getName() {
            return "time.now";
        }

        @Override
        public Object getValue(SystemVariableContext context) {
            return System.currentTimeMillis();
        }
    }

    private static class CorrelationIdProvider implements SystemVariableProvider {
        @Override
        public String getName() {
            return "correlationId";
        }

        @Override
        public Object getValue(SystemVariableContext context) {
            return context.getCorrelationIdFromBuilder().orElse(null);
        }
    }
}
