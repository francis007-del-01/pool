package com.pool.priority;

import java.util.Objects;

/**
 * Combined priority key for task comparison.
 * Encapsulates: PathVector + SortValue + SubmittedAt
 * <p>
 * Comparison order:
 * 1. PathVector (lexicographic, lower = higher priority)
 * 2. SortValue (lower = higher priority, after direction adjustment)
 * 3. SubmittedAt (FIFO fallback: older task wins)
 */
public final class PriorityKey implements Comparable<PriorityKey> {

    private final PathVector pathVector;
    private final long sortValue;
    private final long submittedAt;

    /**
     * Create a priority key.
     *
     * @param pathVector  Path through the priority tree
     * @param sortValue   Sort value from sort-by directive (already direction-adjusted)
     * @param submittedAt Submission timestamp for FIFO fallback
     */
    public PriorityKey(PathVector pathVector, long sortValue, long submittedAt) {
        this.pathVector = Objects.requireNonNull(pathVector, "pathVector cannot be null");
        this.sortValue = sortValue;
        this.submittedAt = submittedAt;
    }

    public PathVector getPathVector() {
        return pathVector;
    }

    public long getSortValue() {
        return sortValue;
    }

    public long getSubmittedAt() {
        return submittedAt;
    }

    @Override
    public int compareTo(PriorityKey other) {
        // 1. Compare path vectors (lexicographic)
        int pathCmp = this.pathVector.compareTo(other.pathVector);
        if (pathCmp != 0) {
            return pathCmp;
        }

        // 2. Compare sort values (lower = higher priority)
        int sortCmp = Long.compare(this.sortValue, other.sortValue);
        if (sortCmp != 0) {
            return sortCmp;
        }

        // 3. FIFO: older task wins (lower submittedAt = higher priority)
        return Long.compare(this.submittedAt, other.submittedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PriorityKey that = (PriorityKey) o;
        return sortValue == that.sortValue &&
                submittedAt == that.submittedAt &&
                pathVector.equals(that.pathVector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathVector, sortValue, submittedAt);
    }

    @Override
    public String toString() {
        return "PriorityKey{" +
                "path=" + pathVector +
                ", sortValue=" + sortValue +
                ", submittedAt=" + submittedAt +
                '}';
    }

    /**
     * Create a priority key for an unmatched task (lowest priority).
     */
    public static PriorityKey unmatched(long submittedAt) {
        return new PriorityKey(PathVector.unmatched(), Long.MAX_VALUE, submittedAt);
    }
}
