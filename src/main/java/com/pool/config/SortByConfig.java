package com.pool.config;

/**
 * Configuration for sort-by directive at leaf nodes.
 *
 * @param field     Variable reference (e.g., "$req.priority", "$sys.submittedAt")
 * @param direction Sort direction (ASC = lower first, DESC = higher first)
 */
public record SortByConfig(
        String field,
        SortDirection direction
) {
    /**
     * Default sort-by: FIFO based on submission time.
     */
    public static SortByConfig fifo() {
        return new SortByConfig("$sys.submittedAt", SortDirection.ASC);
    }
}
