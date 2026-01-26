package com.pool.strategy;

/**
 * Available priority strategy types.
 */
public enum StrategyType {
    /**
     * FIFO Strategy: Simple priority queue.
     * Tasks ordered by: (1) Priority Score, (2) Submission Time
     * Oldest task with highest priority wins.
     * No additional overhead or daemon threads.
     */
    FIFO,

    /**
     * TIME_BASED Strategy (Future): Priority aging.
     * Boosts task priority based on wait time to prevent starvation.
     * Requires periodic re-evaluation daemon.
     */
    TIME_BASED,

    /**
     * BUCKET_BASED Strategy (Future): Multi-level queues.
     * Multiple capacity-limited priority buckets with promotion.
     * Tasks can be promoted to higher buckets over time.
     */
    BUCKET_BASED
}
