package com.pool.policy;

import com.pool.config.PoolConfig;

/**
 * Factory for creating PolicyEngine implementations.
 */
public final class PolicyEngineFactory {

    private PolicyEngineFactory() {
    }

    public static PolicyEngine create(PoolConfig config) {
        return new DefaultPolicyEngine(config);
    }
}
