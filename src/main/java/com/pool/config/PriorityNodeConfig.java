package com.pool.config;

import lombok.Data;

import java.util.List;

/**
 * Configuration for a node in the priority tree.
 */
@Data
public class PriorityNodeConfig {

    /**
     * Node name (e.g., "L1.NORTH_AMERICA", "L2.PLATINUM").
     */
    private String name;

    /**
     * Condition expression string (e.g., '$req.region == "US"').
     */
    private String condition = "true";

    /**
     * Child nodes (null or empty = leaf node).
     */
    private List<PriorityNodeConfig> nestedLevels;

    /**
     * Sort-by directive (only valid on leaf nodes).
     */
    private SortByConfig sortBy;

    /**
     * Target executor ID for TPS-based routing (only valid on leaf nodes).
     */
    private String executor;

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
