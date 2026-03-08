package com.pool.config;

import com.pool.strategy.StrategyType;
import lombok.Data;

/**
 * Configuration for priority strategy.
 */
@Data
public class StrategyConfig {

    /**
     * Strategy type (FIFO, TIME_BASED, BUCKET_BASED).
     */
    private StrategyType type = StrategyType.FIFO;

    /**
     * Configuration for TIME_BASED strategy (future).
     */
    private TimeBasedConfig timeBased;

    /**
     * Configuration for BUCKET_BASED strategy (future).
     */
    private BucketBasedConfig bucketBased;

    /**
     * Create default FIFO strategy config.
     */
    public static StrategyConfig fifo() {
        return new StrategyConfig();
    }

    /**
     * Configuration for TIME_BASED strategy (future implementation).
     */
    @Data
    public static class TimeBasedConfig {
        /** How often to re-evaluate priorities (ms). */
        private long agingIntervalMs = 5000;
        /** Priority boost per interval. */
        private long boostPerInterval = 1;
        /** Maximum total boost allowed. */
        private long maxBoost = 100;
    }

    /**
     * Configuration for BUCKET_BASED strategy (future implementation).
     */
    @Data
    public static class BucketBasedConfig {
        /** Number of priority buckets. */
        private int bucketCount = 4;
        /** Capacity of each bucket (index 0 = highest priority). */
        private int[] bucketCapacities = {100, 500, 2000, Integer.MAX_VALUE};
        /** How often to promote tasks between buckets. */
        private long promotionIntervalMs = 10000;
    }
}
