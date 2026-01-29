package com.pool.policy;

import com.pool.config.PoolConfig;
import com.pool.config.SyntaxUsed;

/**
 * Factory for creating PolicyEngine implementations based on config.
 */
public final class PolicyEngineFactory {

    private PolicyEngineFactory() {
    }

    public static PolicyEngine create(PoolConfig config) {
        if (config.syntaxUsed() == SyntaxUsed.CONDITION_EXPR) {
            return new ExpressionPolicyEngine(config);
        }
        return new DefaultPolicyEngine(config);
    }
}
