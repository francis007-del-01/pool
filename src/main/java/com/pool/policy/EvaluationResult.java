package com.pool.policy;

import com.pool.priority.PriorityKey;

/**
 * Result of policy evaluation.
 */
public interface EvaluationResult {

    /**
     * Get the matched path through the priority tree.
     * May be null if no path matched (unmatched task).
     */
    MatchedPath getMatchedPath();

    /**
     * Get the combined priority key for comparison.
     */
    PriorityKey getPriorityKey();

    /**
     * Check if a valid leaf node was matched.
     */
    boolean isMatched();

    /**
     * Get human-readable explanation of the priority decision.
     */
    String getExplanation();
}
