package com.pool.priority;

import com.pool.condition.Condition;
import com.pool.condition.ConditionEvaluator;
import com.pool.config.PriorityNodeConfig;
import com.pool.config.SortByConfig;
import com.pool.core.TaskContext;
import com.pool.policy.MatchedNode;
import com.pool.policy.MatchedPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Traverses the priority tree to find a matching leaf path.
 * <p>
 * Rules:
 * - Must match from root to leaf (leaf = node without nested-levels)
 * - If a node matches but no child matches, reject and try next sibling
 * - First matching branch at each level wins (declaration order = priority order)
 * - Branch index is 1-based (1 = highest priority)
 */
public class TreeTraverser {

    private static final Logger log = LoggerFactory.getLogger(TreeTraverser.class);

    private final ConditionEvaluator conditionEvaluator;

    public TreeTraverser(ConditionEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

    /**
     * Traverse the priority tree and find a matching leaf path.
     *
     * @param nodes   List of root nodes in the priority tree
     * @param context Task context for condition evaluation
     * @return Matched path if found, empty if no path matches
     */
    public Optional<MatchedPath> traverse(List<PriorityNodeConfig> nodes, TaskContext context) {
        if (nodes == null || nodes.isEmpty()) {
            log.debug("Priority tree is empty, no path matched");
            return Optional.empty();
        }

        List<MatchedNode> path = new ArrayList<>();
        LeafResult leafResult = traverseRecursive(nodes, context, path, 0);
        
        if (leafResult != null) {
            MatchedPath matchedPath = new MatchedPath(path, leafResult.sortBy, leafResult.queueName);
            log.debug("Matched path: {} -> queue: {} for task {}", 
                    matchedPath.toPathString(), leafResult.queueName, context.getTaskId());
            return Optional.of(matchedPath);
        }

        log.debug("No matching path found for task {}", context.getTaskId());
        return Optional.empty();
    }

    /** Internal result containing both sortBy and queue name from leaf node. */
    private record LeafResult(SortByConfig sortBy, String queueName) {}

    /**
     * Recursive traversal of the tree.
     *
     * @param nodes   Nodes at current level
     * @param context Task context
     * @param path    Accumulated path (mutated)
     * @param level   Current level (0-based)
     * @return LeafResult if a leaf is matched, null otherwise
     */
    private static final int MAX_DEPTH = 9;

    private LeafResult traverseRecursive(List<PriorityNodeConfig> nodes, TaskContext context,
                                            List<MatchedNode> path, int level) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }

        if (level > MAX_DEPTH) {
            log.warn("Max tree depth ({}) exceeded, possible circular reference in config", MAX_DEPTH + 1);
            return null;
        }

        // Try each node in order (first match wins, index is priority)
        for (int i = 0; i < nodes.size(); i++) {
            PriorityNodeConfig node = nodes.get(i);
            int branchIndex = i + 1; // 1-based index

            // Evaluate condition
            Condition condition = conditionEvaluator.create(node.condition());
            boolean matches = condition.evaluate(context);

            log.trace("Level {}, Node {} '{}': condition {} = {}", 
                    level, branchIndex, node.name(), condition, matches);

            if (!matches) {
                continue; // Try next sibling
            }

            // Condition matched - check if this is a leaf or has children
            if (node.isLeaf()) {
                // Leaf node - we have a complete match
                path.add(new MatchedNode(node.name(), branchIndex));
                return new LeafResult(node.getEffectiveSortBy(), node.queue());
            }

            // Non-leaf node - try to match children
            LeafResult childResult = traverseRecursive(
                    node.nestedLevels(), context, path, level + 1);

            if (childResult != null) {
                // Child matched - add this node to path and return
                path.add(0, new MatchedNode(node.name(), branchIndex)); // Insert at beginning
                return childResult;
            }

            // No child matched - reject this branch and try next sibling
            log.trace("Level {}, Node '{}': matched but no child matched, trying next sibling", 
                    level, node.name());
        }

        // No match found at this level
        return null;
    }
}
