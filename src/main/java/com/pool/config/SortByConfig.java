package com.pool.config;

import lombok.Data;

/**
 * Configuration for sort-by directive at leaf nodes.
 */
@Data
public class SortByConfig {

    /**
     * Variable reference (e.g., "$req.priority", "$sys.submittedAt").
     */
    private String field;

    /**
     * Sort direction (ASC = lower first, DESC = higher first).
     */
    private SortDirection direction;

    /**
     * Default sort-by: FIFO based on submission time.
     */
    public static SortByConfig fifo() {
        SortByConfig config = new SortByConfig();
        config.setField("$sys.submittedAt");
        config.setDirection(SortDirection.ASC);
        return config;
    }
}
