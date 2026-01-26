package com.pool.priority;

import java.util.Arrays;

/**
 * Immutable path vector representing matched branch indices in the priority tree.
 * Fixed length of 10 (supporting up to 10 levels).
 * Lower value at earlier index = higher priority.
 * <p>
 * Comparison is lexicographic: first differing index determines order.
 * Example: [1,2,0,0,...] beats [2,1,0,0,...] because index 0 differs (1 < 2)
 */
public final class PathVector implements Comparable<PathVector> {

    /**
     * Maximum supported tree depth.
     */
    public static final int MAX_LEVELS = 10;

    /**
     * Value used for unmatched paths (lowest priority).
     */
    public static final int UNMATCHED_VALUE = 999;

    private final int[] vector;

    private PathVector(int[] vector) {
        this.vector = vector;
    }

    /**
     * Get the branch index at a specific level.
     *
     * @param level Level index (0-9)
     * @return Branch index at that level
     */
    public int get(int level) {
        if (level < 0 || level >= MAX_LEVELS) {
            throw new IndexOutOfBoundsException("Level must be between 0 and " + (MAX_LEVELS - 1));
        }
        return vector[level];
    }

    /**
     * Get the effective depth (number of non-zero levels).
     */
    public int getDepth() {
        int depth = 0;
        for (int i = 0; i < MAX_LEVELS; i++) {
            if (vector[i] > 0) {
                depth = i + 1;
            }
        }
        return depth;
    }

    /**
     * Create a copy of the internal vector.
     */
    public int[] toArray() {
        return Arrays.copyOf(vector, MAX_LEVELS);
    }

    @Override
    public int compareTo(PathVector other) {
        // Lexicographic comparison: lower value wins at first differing index
        for (int i = 0; i < MAX_LEVELS; i++) {
            int cmp = Integer.compare(this.vector[i], other.vector[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathVector that = (PathVector) o;
        return Arrays.equals(vector, that.vector);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(vector);
    }

    @Override
    public String toString() {
        return Arrays.toString(vector);
    }

    /**
     * Create a PathVector from individual values.
     * Missing values default to 0.
     */
    public static PathVector of(int... values) {
        int[] vector = new int[MAX_LEVELS];
        int copyLength = Math.min(values.length, MAX_LEVELS);
        System.arraycopy(values, 0, vector, 0, copyLength);
        return new PathVector(vector);
    }

    /**
     * Create a PathVector representing an unmatched path (lowest priority).
     * All values set to UNMATCHED_VALUE (999).
     */
    public static PathVector unmatched() {
        int[] vector = new int[MAX_LEVELS];
        Arrays.fill(vector, UNMATCHED_VALUE);
        return new PathVector(vector);
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for PathVector.
     */
    public static class Builder {
        private final int[] vector = new int[MAX_LEVELS];

        /**
         * Set the branch index at a specific level.
         *
         * @param level       Level index (0-9)
         * @param branchIndex Branch index (1 = highest priority at this level)
         */
        public Builder set(int level, int branchIndex) {
            if (level >= 0 && level < MAX_LEVELS) {
                vector[level] = branchIndex;
            }
            return this;
        }

        /**
         * Build the PathVector.
         */
        public PathVector build() {
            return new PathVector(Arrays.copyOf(vector, MAX_LEVELS));
        }
    }
}
