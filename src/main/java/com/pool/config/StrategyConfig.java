package com.pool.config;

import com.pool.strategy.StrategyType;

/**
 * Configuration for priority strategy.
 *
 * @param type Strategy type (FIFO, TIME_BASED, BUCKET_BASED)
 * @param timeBasedConfig Configuration for TIME_BASED strategy (future)
 * @param bucketBasedConfig Configuration for BUCKET_BASED strategy (future)
 */
public record StrategyConfig(
        StrategyType type,
        TimeBasedConfig timeBasedConfig,
        BucketBasedConfig bucketBasedConfig
) {
    /**
     * Create default FIFO strategy config.
     */
    public static StrategyConfig fifo() {
        return new StrategyConfig(StrategyType.FIFO, null, null);
    }

    /**
     * Configuration for TIME_BASED strategy (future implementation).
     *
     * @param agingIntervalMs How often to re-evaluate priorities (ms)
     * @param boostPerInterval Priority boost per interval
     * @param maxBoost Maximum total boost allowed
     */
    public record TimeBasedConfig(
            long agingIntervalMs,
            long boostPerInterval,
            long maxBoost
    ) {
        public static TimeBasedConfig defaults() {
            return new TimeBasedConfig(5000, 1, 100);
        }
    }

    /**
     * Configuration for BUCKET_BASED strategy (future implementation).
     *
     * @param bucketCount Number of priority buckets
     * @param bucketCapacities Capacity of each bucket (index 0 = highest priority)
     * @param promotionIntervalMs How often to promote tasks between buckets
     */
    public record BucketBasedConfig(
            int bucketCount,
            int[] bucketCapacities,
            long promotionIntervalMs
    ) {
        public static BucketBasedConfig defaults() {
            return new BucketBasedConfig(4, new int[]{100, 500, 2000, Integer.MAX_VALUE}, 10000);
        }
    }
}
