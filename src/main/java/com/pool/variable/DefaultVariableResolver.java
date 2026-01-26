package com.pool.variable;

import com.pool.core.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Default implementation of VariableResolver.
 */
public class DefaultVariableResolver implements VariableResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultVariableResolver.class);

    @Override
    public Optional<Object> resolve(String reference, TaskContext context) {
        if (reference == null || reference.isEmpty() || context == null) {
            return Optional.empty();
        }

        try {
            VariableSource source = VariableSource.fromReference(reference);
            String name = VariableSource.extractName(reference);

            return switch (source) {
                case REQUEST -> context.getRequestVariable(name);
                case SYSTEM -> context.getSystemVariable(name);
                case CONTEXT -> context.getContextVariable(name);
            };
        } catch (IllegalArgumentException e) {
            log.warn("Invalid variable reference: {}", reference);
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> resolve(String reference, TaskContext context, Class<T> type) {
        return resolve(reference, context)
                .filter(value -> type.isInstance(value))
                .map(value -> (T) value);
    }

    @Override
    public Optional<Long> resolveAsLong(String reference, TaskContext context) {
        return resolve(reference, context)
                .flatMap(this::convertToLong);
    }

    @Override
    public Optional<Double> resolveAsDouble(String reference, TaskContext context) {
        return resolve(reference, context)
                .flatMap(this::convertToDouble);
    }

    private Optional<Long> convertToLong(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Long l) {
            return Optional.of(l);
        }
        if (value instanceof Integer i) {
            return Optional.of(i.longValue());
        }
        if (value instanceof Double d) {
            return Optional.of(d.longValue());
        }
        if (value instanceof Float f) {
            return Optional.of(f.longValue());
        }
        if (value instanceof Short s) {
            return Optional.of(s.longValue());
        }
        if (value instanceof Byte b) {
            return Optional.of(b.longValue());
        }
        if (value instanceof Number n) {
            return Optional.of(n.longValue());
        }
        if (value instanceof String s) {
            try {
                return Optional.of(Long.parseLong(s));
            } catch (NumberFormatException e) {
                try {
                    return Optional.of((long) Double.parseDouble(s));
                } catch (NumberFormatException e2) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Double> convertToDouble(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Double d) {
            return Optional.of(d);
        }
        if (value instanceof Float f) {
            return Optional.of(f.doubleValue());
        }
        if (value instanceof Long l) {
            return Optional.of(l.doubleValue());
        }
        if (value instanceof Integer i) {
            return Optional.of(i.doubleValue());
        }
        if (value instanceof Short s) {
            return Optional.of(s.doubleValue());
        }
        if (value instanceof Byte b) {
            return Optional.of(b.doubleValue());
        }
        if (value instanceof Number n) {
            return Optional.of(n.doubleValue());
        }
        if (value instanceof String s) {
            try {
                return Optional.of(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
