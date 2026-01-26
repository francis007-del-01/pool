package com.pool.policy;

import com.pool.condition.ConditionEvaluator;
import com.pool.condition.DefaultConditionEvaluator;
import com.pool.config.PoolConfig;
import com.pool.core.TaskContext;
import com.pool.priority.PriorityCalculator;
import com.pool.priority.PriorityKey;
import com.pool.priority.TreeTraverser;
import com.pool.variable.DefaultVariableResolver;
import com.pool.variable.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Default implementation of PolicyEngine.
 * Evaluates the priority tree and calculates task priority at submission time.
 */
public class DefaultPolicyEngine implements PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultPolicyEngine.class);

    private volatile PoolConfig config;
    private final VariableResolver variableResolver;
    private final ConditionEvaluator conditionEvaluator;
    private final TreeTraverser treeTraverser;
    private final PriorityCalculator priorityCalculator;

    public DefaultPolicyEngine(PoolConfig config) {
        this.config = config;
        this.variableResolver = new DefaultVariableResolver();
        this.conditionEvaluator = new DefaultConditionEvaluator(variableResolver);
        this.treeTraverser = new TreeTraverser(conditionEvaluator);
        this.priorityCalculator = new PriorityCalculator(variableResolver);
        
        log.info("PolicyEngine initialized with config: {} v{}", config.name(), config.version());
    }

    @Override
    public EvaluationResult evaluate(TaskContext context) {
        log.debug("Evaluating priority for task: {}", context.getTaskId());

        // Traverse the priority tree to find matching path
        Optional<MatchedPath> matchedPath = treeTraverser.traverse(
                config.priorityTree(), context);

        // Calculate priority key
        PriorityKey priorityKey;
        EvaluationResult result;

        if (matchedPath.isPresent()) {
            priorityKey = priorityCalculator.calculatePriorityKey(matchedPath.get(), context);
            result = DefaultEvaluationResult.matched(matchedPath.get(), priorityKey);
            log.debug("Task {} matched path: {}, priority: {}", 
                    context.getTaskId(), matchedPath.get().toPathString(), priorityKey.getPathVector());
        } else {
            // No match - assign lowest priority
            priorityKey = PriorityKey.unmatched(context.getSubmittedAt());
            result = DefaultEvaluationResult.unmatched(priorityKey);
            log.warn("Task {} did not match any path, assigned lowest priority", context.getTaskId());
        }

        return result;
    }

    @Override
    public void reload() {
        // This will be called when config changes
        // For now, config is passed at construction time
        // In Spring integration, this would reload from the config source
        log.info("PolicyEngine reload requested");
    }

    /**
     * Update the configuration (used for hot reload).
     */
    public void updateConfig(PoolConfig newConfig) {
        this.config = newConfig;
        log.info("PolicyEngine config updated to: {} v{}", newConfig.name(), newConfig.version());
    }

    /**
     * Get current configuration.
     */
    public PoolConfig getConfig() {
        return config;
    }
}
