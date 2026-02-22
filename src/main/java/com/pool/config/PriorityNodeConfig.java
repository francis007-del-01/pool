package com.pool.config;

import java.util.List;

/**
 * Configuration for a node in the priority tree.
 *
 * @param name         Node name (e.g., "L1.NORTH_AMERICA", "L2.PLATINUM")
 * @param condition    Condition expression string (e.g., '$req.region == "US"')
 * @param nestedLevels Child nodes (null or empty = leaf node)
 * @param sortBy       Sort-by directive (only valid on leaf nodes)
 * @param executor     Target executor ID for TPS-based routing (only valid on leaf nodes)
 */
public record PriorityNodeConfig(
        String name,
        String condition,
        List<PriorityNodeConfig> nestedLevels,
        SortByConfig sortBy,
        String executor
) {
    /**
     * Check if this node is a leaf (no nested levels).
     */
    public boolean isLeaf() {
        return nestedLevels == null || nestedLevels.isEmpty();
    }

    /**
     * Get the sort-by config, defaulting to FIFO if not specified on a leaf.
     */
    public SortByConfig getEffectiveSortBy() {
        if (sortBy != null) {
            return sortBy;
        }
        return isLeaf() ? SortByConfig.fifo() : null;
    }
}
