package com.pool.condition;

import com.pool.condition.impl.*;
import com.pool.config.ConditionConfig;
import com.pool.exception.ConfigurationException;
import com.pool.variable.VariableResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of ConditionEvaluator.
 * Factory that creates Condition instances from configuration.
 */
public class DefaultConditionEvaluator implements ConditionEvaluator {

    private final VariableResolver variableResolver;

    public DefaultConditionEvaluator(VariableResolver variableResolver) {
        this.variableResolver = variableResolver;
    }

    @Override
    public Condition create(ConditionConfig config) {
        if (config == null) {
            throw new ConfigurationException("Condition configuration cannot be null");
        }

        ConditionType type = config.type();
        if (type == null) {
            throw new ConfigurationException("Condition type cannot be null");
        }

        return switch (type) {
            case ALWAYS_TRUE -> AlwaysTrueCondition.INSTANCE;

            case EQUALS -> createEqualsCondition(config);
            case NOT_EQUALS -> createNotEqualsCondition(config);

            case GREATER_THAN -> createComparisonCondition(config, ConditionType.GREATER_THAN);
            case GREATER_THAN_OR_EQUALS -> createComparisonCondition(config, ConditionType.GREATER_THAN_OR_EQUALS);
            case LESS_THAN -> createComparisonCondition(config, ConditionType.LESS_THAN);
            case LESS_THAN_OR_EQUALS -> createComparisonCondition(config, ConditionType.LESS_THAN_OR_EQUALS);
            case BETWEEN -> createBetweenCondition(config);

            case IN -> createInCondition(config);
            case NOT_IN -> createNotInCondition(config);
            case CONTAINS -> createContainsCondition(config);

            case REGEX -> createRegexCondition(config);
            case STARTS_WITH -> createStartsWithCondition(config);
            case ENDS_WITH -> createEndsWithCondition(config);

            case EXISTS -> createExistsCondition(config);
            case IS_NULL -> createIsNullCondition(config);

            case AND -> createAndCondition(config);
            case OR -> createOrCondition(config);
            case NOT -> createNotCondition(config);
        };
    }

    private Condition createEqualsCondition(ConditionConfig config) {
        validateField(config);
        validateValue(config);
        return new EqualsCondition(config.field(), config.value(), variableResolver);
    }

    private Condition createNotEqualsCondition(ConditionConfig config) {
        validateField(config);
        validateValue(config);
        return new NotEqualsCondition(config.field(), config.value(), variableResolver);
    }

    private Condition createComparisonCondition(ConditionConfig config, ConditionType type) {
        validateField(config);
        validateNumericValue(config);
        Number threshold = (Number) config.value();
        return new ComparisonCondition(config.field(), threshold, type, variableResolver);
    }

    private Condition createBetweenCondition(ConditionConfig config) {
        validateField(config);
        validateNumericValue(config);
        if (config.value2() == null || !(config.value2() instanceof Number)) {
            throw new ConfigurationException("BETWEEN condition requires numeric value2 (upper bound)");
        }
        Number lowerBound = (Number) config.value();
        Number upperBound = (Number) config.value2();
        return new BetweenCondition(config.field(), lowerBound, upperBound, variableResolver);
    }

    private Condition createInCondition(ConditionConfig config) {
        validateField(config);
        validateValues(config);
        return new InCondition(config.field(), config.values(), variableResolver);
    }

    private Condition createNotInCondition(ConditionConfig config) {
        validateField(config);
        validateValues(config);
        return new NotInCondition(config.field(), config.values(), variableResolver);
    }

    private Condition createContainsCondition(ConditionConfig config) {
        validateField(config);
        validateValue(config);
        return new ContainsCondition(config.field(), config.value(), variableResolver);
    }

    private Condition createRegexCondition(ConditionConfig config) {
        validateField(config);
        validatePattern(config);
        return new RegexCondition(config.field(), config.pattern(), variableResolver);
    }

    private Condition createStartsWithCondition(ConditionConfig config) {
        validateField(config);
        validatePattern(config);
        return new StartsWithCondition(config.field(), config.pattern(), variableResolver);
    }

    private Condition createEndsWithCondition(ConditionConfig config) {
        validateField(config);
        validatePattern(config);
        return new EndsWithCondition(config.field(), config.pattern(), variableResolver);
    }

    private Condition createExistsCondition(ConditionConfig config) {
        validateField(config);
        return new ExistsCondition(config.field(), variableResolver);
    }

    private Condition createIsNullCondition(ConditionConfig config) {
        validateField(config);
        return new IsNullCondition(config.field(), variableResolver);
    }

    private Condition createAndCondition(ConditionConfig config) {
        validateConditions(config);
        List<Condition> nestedConditions = createNestedConditions(config.conditions());
        return new AndCondition(nestedConditions);
    }

    private Condition createOrCondition(ConditionConfig config) {
        validateConditions(config);
        List<Condition> nestedConditions = createNestedConditions(config.conditions());
        return new OrCondition(nestedConditions);
    }

    private Condition createNotCondition(ConditionConfig config) {
        validateConditions(config);
        if (config.conditions().size() != 1) {
            throw new ConfigurationException("NOT condition must have exactly one nested condition");
        }
        Condition nestedCondition = create(config.conditions().get(0));
        return new NotCondition(nestedCondition);
    }

    private List<Condition> createNestedConditions(List<ConditionConfig> configs) {
        List<Condition> conditions = new ArrayList<>();
        for (ConditionConfig cfg : configs) {
            conditions.add(create(cfg));
        }
        return conditions;
    }

    // Validation helpers

    private void validateField(ConditionConfig config) {
        if (config.field() == null || config.field().isBlank()) {
            throw new ConfigurationException(config.type() + " condition requires a field");
        }
    }

    private void validateValue(ConditionConfig config) {
        if (config.value() == null) {
            throw new ConfigurationException(config.type() + " condition requires a value");
        }
    }

    private void validateNumericValue(ConditionConfig config) {
        if (config.value() == null || !(config.value() instanceof Number)) {
            throw new ConfigurationException(config.type() + " condition requires a numeric value");
        }
    }

    private void validateValues(ConditionConfig config) {
        if (config.values() == null || config.values().isEmpty()) {
            throw new ConfigurationException(config.type() + " condition requires a values list");
        }
    }

    private void validatePattern(ConditionConfig config) {
        if (config.pattern() == null || config.pattern().isBlank()) {
            throw new ConfigurationException(config.type() + " condition requires a pattern");
        }
    }

    private void validateConditions(ConditionConfig config) {
        if (config.conditions() == null || config.conditions().isEmpty()) {
            throw new ConfigurationException(config.type() + " condition requires nested conditions");
        }
    }
}
