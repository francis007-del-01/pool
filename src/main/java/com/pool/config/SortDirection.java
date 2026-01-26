package com.pool.config;

/**
 * Sort direction for sort-by directive.
 */
public enum SortDirection {
    /**
     * Ascending: Lower value = higher priority.
     * Use for: deadline (earlier deadline first), submittedAt (FIFO)
     */
    ASC,

    /**
     * Descending: Higher value = higher priority.
     * Use for: priority score (higher priority first), business value
     */
    DESC
}
