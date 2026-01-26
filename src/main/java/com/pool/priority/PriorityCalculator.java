package com.pool.priority;

import com.pool.config.SortByConfig;
import com.pool.config.SortDirection;
import com.pool.core.TaskContext;
import com.pool.policy.MatchedNode;
import com.pool.policy.MatchedPath;
import com.pool.variable.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Calculates priority components from matched path and context.
 */
public class PriorityCalculator {

    private static final Logger log = LoggerFactory.getLogger(PriorityCalculator.class);

    private final VariableResolver variableResolver;

    public PriorityCalculator(VariableResolver variableResolver) {
        this.variableResolver = variableResolver;
    }

    /**
     * Calculate the path vector from a matched path.
     *
     * @param matchedPath Matched path through the tree
     * @return Path vector with branch indices
     */
    public PathVector calculatePathVector(MatchedPath matchedPath) {
        PathVector.Builder builder = PathVector.builder();
        
        for (int i = 0; i < matchedPath.nodes().size(); i++) {
            MatchedNode node = matchedPath.nodes().get(i);
            builder.set(i, node.branchIndex());
        }
        
        return builder.build();
    }

    /**
     * Calculate the sort value from sort-by configuration.
     * Applies direction: ASC uses value as-is, DESC negates it.
     *
     * @param sortBy  Sort-by configuration
     * @param context Task context
     * @return Sort value (lower = higher priority)
     */
    public long calculateSortValue(SortByConfig sortBy, TaskContext context) {
        if (sortBy == null) {
            // Default to FIFO
            return context.getSubmittedAt();
        }

        Optional<Long> value = variableResolver.resolveAsLong(sortBy.field(), context);
        
        if (value.isEmpty()) {
            log.warn("Sort-by field '{}' not found or not numeric, using submittedAt as fallback", 
                    sortBy.field());
            return context.getSubmittedAt();
        }

        long rawValue = value.get();
        
        // Apply direction: DESC means higher value = higher priority
        // We negate for DESC so that higher values become lower (since lower = higher priority)
        if (sortBy.direction() == SortDirection.DESC) {
            return -rawValue;
        }
        
        return rawValue;
    }

    /**
     * Build a complete priority key from matched path and context.
     *
     * @param matchedPath Matched path (may be null for unmatched)
     * @param context     Task context
     * @return Priority key for comparison
     */
    public PriorityKey calculatePriorityKey(MatchedPath matchedPath, TaskContext context) {
        if (matchedPath == null) {
            // Unmatched task - lowest priority
            return PriorityKey.unmatched(context.getSubmittedAt());
        }

        PathVector pathVector = calculatePathVector(matchedPath);
        long sortValue = calculateSortValue(matchedPath.sortBy(), context);
        
        return new PriorityKey(pathVector, sortValue, context.getSubmittedAt());
    }
}
