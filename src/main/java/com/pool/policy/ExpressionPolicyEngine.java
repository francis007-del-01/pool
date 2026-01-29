package com.pool.policy;

import com.pool.condition.Condition;
import com.pool.condition.ConditionEvaluator;
import com.pool.condition.DefaultConditionEvaluator;
import com.pool.config.PoolConfig;
import com.pool.config.PriorityNodeConfig;
import com.pool.core.TaskContext;
import com.pool.priority.PathVector;
import com.pool.priority.PriorityCalculator;
import com.pool.priority.PriorityKey;
import com.pool.variable.DefaultVariableResolver;
import com.pool.variable.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * PolicyEngine for expression-based condition syntax.
 * Evaluates flat priority rules sequentially (first match wins).
 * Each rule's condition-expr is parsed into a Condition tree and evaluated
 * using existing condition evaluation logic.
 */
public class ExpressionPolicyEngine implements PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(ExpressionPolicyEngine.class);

    private final PoolConfig config;
    private final VariableResolver variableResolver;
    private final ConditionEvaluator conditionEvaluator;
    private final PriorityCalculator priorityCalculator;
    private final List<PriorityRule> rules;

    public ExpressionPolicyEngine(PoolConfig config) {
        this.config = config;
        this.variableResolver = new DefaultVariableResolver();
        this.conditionEvaluator = new DefaultConditionEvaluator(variableResolver);
        this.priorityCalculator = new PriorityCalculator(variableResolver);
        this.rules = buildRules(config);

        log.info("ExpressionPolicyEngine initialized with {} rules", rules.size());
    }

    private List<PriorityRule> buildRules(PoolConfig config) {
        List<PriorityRule> rulesList = new ArrayList<>();
        List<PriorityNodeConfig> priorityTree = config.priorityTree();

        for (int i = 0; i < priorityTree.size(); i++) {
            PriorityNodeConfig node = priorityTree.get(i);
            
            // Convert ConditionConfig to Condition
            Condition condition = conditionEvaluator.create(node.condition());
            
            rulesList.add(new PriorityRule(condition, node, i));
            log.debug("Built rule {} for node '{}'", i, node.name());
        }

        return rulesList;
    }

    @Override
    public EvaluationResult evaluate(TaskContext context) {
        log.debug("Evaluating priority for task: {}", context.getTaskId());

        // Evaluate rules sequentially until first match
        for (PriorityRule rule : rules) {
            if (rule.condition().evaluate(context)) {
                // Matched! Create path with single index
                // Index at level 0, 1-based for proper priority ordering
                PathVector pathVector = PathVector.builder()
                        .set(0, rule.index() + 1)
                        .build();

                MatchedNode matchedNode = new MatchedNode(
                        rule.node().name(),
                        rule.index() + 1  // 1-based branch index
                );

                MatchedPath matchedPath = new MatchedPath(
                        List.of(matchedNode),
                        rule.node().getEffectiveSortBy(),
                        rule.node().queue()
                );

                // Calculate priority key
                PriorityKey priorityKey = priorityCalculator.calculatePriorityKey(matchedPath, context);

                log.debug("Task {} matched rule {} ({}), priority: {}",
                        context.getTaskId(), rule.index(), rule.node().name(), pathVector);

                return DefaultEvaluationResult.matched(matchedPath, priorityKey);
            }
        }

        // No match - assign lowest priority
        PriorityKey priorityKey = PriorityKey.unmatched(context.getSubmittedAt());
        log.warn("Task {} did not match any rule, assigned lowest priority", context.getTaskId());

        return DefaultEvaluationResult.unmatched(priorityKey);
    }

    @Override
    public void reload() {
        log.info("ExpressionPolicyEngine reload requested");
    }

    /**
     * A priority rule: condition tree + config + index.
     */
    private record PriorityRule(Condition condition, PriorityNodeConfig node, int index) {
    }
}
