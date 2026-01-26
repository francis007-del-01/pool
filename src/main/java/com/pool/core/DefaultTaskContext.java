package com.pool.core;

import com.pool.variable.SystemVariableContext;
import com.pool.variable.SystemVariableProviderFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of TaskContext.
 * Immutable after construction.
 */
public final class DefaultTaskContext implements TaskContext {

    private final String taskId;
    private final String correlationId;
    private final long submittedAt;
    private final Map<String, Object> requestVariables;
    private final Map<String, Object> contextVariables;
    private final Map<String, Object> systemVariables;

    private DefaultTaskContext(Builder builder) {
        // Build system variables first - providers compute all values
        SystemVariableContext sysVarContext = new SystemVariableContext(
                builder.taskId, builder.correlationId);
        this.systemVariables = Collections.unmodifiableMap(
                SystemVariableProviderFactory.computeAll(sysVarContext));

        // Extract values from computed system variables
        this.taskId = (String) this.systemVariables.get("taskId");
        this.submittedAt = (Long) this.systemVariables.get("submittedAt");
        this.correlationId = (String) this.systemVariables.get("correlationId");

        this.requestVariables = Collections.unmodifiableMap(new HashMap<>(builder.requestVariables));
        this.contextVariables = Collections.unmodifiableMap(new HashMap<>(builder.contextVariables));
    }

    @Override
    public Optional<Object> getSystemVariable(String name) {
        return Optional.ofNullable(systemVariables.get(name));
    }

    @Override
    public Map<String, Object> getSystemVariables() {
        return systemVariables;
    }

    @Override
    public Optional<Object> getRequestVariable(String name) {
        return Optional.ofNullable(requestVariables.get(name));
    }

    @Override
    public Map<String, Object> getRequestVariables() {
        return requestVariables;
    }

    @Override
    public Optional<Object> getContextVariable(String name) {
        return Optional.ofNullable(contextVariables.get(name));
    }

    @Override
    public Map<String, Object> getContextVariables() {
        return contextVariables;
    }

    @Override
    public String getTaskId() {
        return taskId;
    }

    @Override
    public long getSubmittedAt() {
        return submittedAt;
    }

    @Override
    public Optional<String> getCorrelationId() {
        return Optional.ofNullable(correlationId);
    }

    @Override
    public String toString() {
        return "TaskContext{" +
                "taskId='" + taskId + '\'' +
                ", submittedAt=" + submittedAt +
                ", requestVariables=" + requestVariables +
                ", contextVariables=" + contextVariables +
                '}';
    }

    /**
     * Builder for DefaultTaskContext.
     */
    public static class Builder implements TaskContext.Builder {
        private String taskId;
        private String correlationId;
        private final Map<String, Object> requestVariables = new HashMap<>();
        private final Map<String, Object> contextVariables = new HashMap<>();

        @Override
        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        @Override
        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        @Override
        public Builder requestVariable(String name, Object value) {
            if (name != null && value != null) {
                this.requestVariables.put(name, value);
            }
            return this;
        }

        @Override
        public Builder requestVariables(Map<String, Object> variables) {
            if (variables != null) {
                this.requestVariables.putAll(variables);
            }
            return this;
        }

        @Override
        public Builder contextVariable(String name, Object value) {
            if (name != null && value != null) {
                this.contextVariables.put(name, value);
            }
            return this;
        }

        @Override
        public Builder contextVariables(Map<String, Object> variables) {
            if (variables != null) {
                this.contextVariables.putAll(variables);
            }
            return this;
        }

        @Override
        public TaskContext build() {
            return new DefaultTaskContext(this);
        }
    }
}
