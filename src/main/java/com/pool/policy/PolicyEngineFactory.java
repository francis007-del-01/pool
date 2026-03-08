package com.pool.policy;

import com.pool.config.PoolConfig;
import com.pool.variable.DefaultVariableResolver;
import com.pool.variable.VariableResolver;

/**
 * Factory for creating PolicyEngine implementations.
 */
public final class PolicyEngineFactory {

    private PolicyEngineFactory() {
    }

    public static PolicyEngine create(PoolConfig config) {
        return create(config, new DefaultVariableResolver());
    }

    public static PolicyEngine create(PoolConfig config, VariableResolver variableResolver) {
        return new DefaultPolicyEngine(config, variableResolver);
    }
}
