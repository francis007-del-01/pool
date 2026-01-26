package com.pool.variable;

import java.util.Optional;

/**
 * Context passed to SystemVariableProviders when computing values.
 * Contains builder-provided inputs that providers might need.
 */
public class SystemVariableContext {

    private final String taskIdFromBuilder;
    private final String correlationIdFromBuilder;

    public SystemVariableContext(String taskIdFromBuilder, String correlationIdFromBuilder) {
        this.taskIdFromBuilder = taskIdFromBuilder;
        this.correlationIdFromBuilder = correlationIdFromBuilder;
    }

    /**
     * Get taskId provided by builder (may be null).
     */
    public Optional<String> getTaskIdFromBuilder() {
        return Optional.ofNullable(taskIdFromBuilder);
    }

    /**
     * Get correlationId provided by builder (may be null).
     */
    public Optional<String> getCorrelationIdFromBuilder() {
        return Optional.ofNullable(correlationIdFromBuilder);
    }
}
