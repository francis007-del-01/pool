package com.pool.strategy;

import com.pool.config.StrategyConfig;
import com.pool.exception.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating PriorityStrategy instances.
 */
public class PriorityStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(PriorityStrategyFactory.class);

    /**
     * Create a PriorityStrategy based on configuration.
     *
     * @param config   Strategy configuration
     * @param capacity Queue capacity
     * @return PriorityStrategy instance
     */
    public static PriorityStrategy create(StrategyConfig config, int capacity) {
        if (config == null) {
            log.info("No strategy config provided, defaulting to FIFO");
            return new FIFOStrategy(capacity);
        }

        StrategyType type = config.type();
        if (type == null) {
            type = StrategyType.FIFO;
        }

        log.info("Creating PriorityStrategy: {}", type);

        return switch (type) {
            case FIFO -> new FIFOStrategy(capacity);
            case TIME_BASED -> throw new ConfigurationException(
                    "TIME_BASED strategy is not yet implemented. Use FIFO for now.");
            case BUCKET_BASED -> throw new ConfigurationException(
                    "BUCKET_BASED strategy is not yet implemented. Use FIFO for now.");
        };
    }

    /**
     * Create a default FIFO strategy.
     */
    public static PriorityStrategy createDefault(int capacity) {
        return new FIFOStrategy(capacity);
    }
}
