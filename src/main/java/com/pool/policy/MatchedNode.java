package com.pool.policy;

/**
 * Represents a matched node in the priority tree path.
 *
 * @param name        Node name (e.g., "L1.NORTH_AMERICA")
 * @param branchIndex 1-based branch index (1 = highest priority at this level)
 */
public record MatchedNode(
        String name,
        int branchIndex
) {
    @Override
    public String toString() {
        return name + "[" + branchIndex + "]";
    }
}
