package com.pool.policy;

import com.pool.config.SortByConfig;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a matched path through the priority tree from root to leaf.
 *
 * @param nodes     List of matched nodes from root to leaf
 * @param sortBy    Sort-by configuration from the leaf node
 * @param queueName Target queue name from the leaf node
 */
public record MatchedPath(
        List<MatchedNode> nodes,
        SortByConfig sortBy,
        String queueName
) {
    /**
     * Get the depth of the matched path.
     */
    public int getDepth() {
        return nodes.size();
    }

    /**
     * Convert to a human-readable path string.
     */
    public String toPathString() {
        return nodes.stream()
                .map(MatchedNode::name)
                .collect(Collectors.joining(" → "));
    }

    /**
     * Convert to a string showing branch indices.
     */
    public String toBranchString() {
        return nodes.stream()
                .map(n -> String.valueOf(n.branchIndex()))
                .collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        return toPathString() + " (sortBy: " + sortBy + ")";
    }
}
