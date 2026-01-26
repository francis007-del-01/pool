package com.pool.policy;

import com.pool.priority.PriorityKey;

/**
 * Default implementation of EvaluationResult.
 */
public class DefaultEvaluationResult implements EvaluationResult {

    private final MatchedPath matchedPath;
    private final PriorityKey priorityKey;
    private final boolean matched;
    private final String explanation;

    private DefaultEvaluationResult(MatchedPath matchedPath, PriorityKey priorityKey, 
                                    boolean matched, String explanation) {
        this.matchedPath = matchedPath;
        this.priorityKey = priorityKey;
        this.matched = matched;
        this.explanation = explanation;
    }

    @Override
    public MatchedPath getMatchedPath() {
        return matchedPath;
    }

    @Override
    public PriorityKey getPriorityKey() {
        return priorityKey;
    }

    @Override
    public boolean isMatched() {
        return matched;
    }

    @Override
    public String getExplanation() {
        return explanation;
    }

    @Override
    public String toString() {
        return "EvaluationResult{" +
                "matched=" + matched +
                ", path=" + (matchedPath != null ? matchedPath.toPathString() : "none") +
                ", priorityKey=" + priorityKey +
                '}';
    }

    /**
     * Create a result for a matched path.
     */
    public static EvaluationResult matched(MatchedPath path, PriorityKey priorityKey) {
        String explanation = "Matched path: " + path.toPathString() + 
                " | Priority: " + priorityKey.getPathVector();
        return new DefaultEvaluationResult(path, priorityKey, true, explanation);
    }

    /**
     * Create a result for an unmatched task.
     */
    public static EvaluationResult unmatched(PriorityKey priorityKey) {
        String explanation = "No matching path found - assigned lowest priority";
        return new DefaultEvaluationResult(null, priorityKey, false, explanation);
    }
}
