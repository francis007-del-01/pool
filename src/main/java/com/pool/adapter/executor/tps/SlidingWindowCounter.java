package com.pool.adapter.executor.tps;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sliding window counter for tracking TPS using unique request identifiers.
 * Uses a time-bucketed approach for efficient counting and cleanup.
 */
public class SlidingWindowCounter {

    private static final int DEFAULT_BUCKET_COUNT = 10;
    
    private final long windowSizeMs;
    private final int bucketCount;
    private final long bucketSizeMs;
    
    // Each bucket contains a set of unique identifiers and their count
    private final ConcurrentHashMap<String, Long>[] buckets;
    private final AtomicInteger[] bucketCounts;
    private final ReentrantLock cleanupLock = new ReentrantLock();
    
    private volatile long lastCleanupTime;

    public SlidingWindowCounter() {
        this(1000); // Default 1 second window
    }

    public SlidingWindowCounter(long windowSizeMs) {
        this(windowSizeMs, DEFAULT_BUCKET_COUNT);
    }

    @SuppressWarnings("unchecked")
    public SlidingWindowCounter(long windowSizeMs, int bucketCount) {
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("Window size must be positive");
        }
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("Bucket count must be positive");
        }
        
        this.windowSizeMs = windowSizeMs;
        this.bucketCount = bucketCount;
        this.bucketSizeMs = windowSizeMs / bucketCount;
        
        this.buckets = new ConcurrentHashMap[bucketCount];
        this.bucketCounts = new AtomicInteger[bucketCount];
        
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new ConcurrentHashMap<>();
            bucketCounts[i] = new AtomicInteger(0);
        }
        
        this.lastCleanupTime = System.currentTimeMillis();
    }

    /**
     * Try to add a request identifier to the window.
     * Returns true if added successfully (identifier is new in current window).
     * Returns false if identifier already exists in current window.
     */
    public boolean tryAdd(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("Identifier cannot be null or empty");
        }
        
        cleanupExpiredBuckets();
        
        long now = System.currentTimeMillis();
        int bucketIndex = getBucketIndex(now);
        
        ConcurrentHashMap<String, Long> bucket = buckets[bucketIndex];
        
        // Check if identifier already exists in any active bucket
        if (containsInWindow(identifier, now)) {
            return false;
        }
        
        // Add to current bucket
        Long previous = bucket.putIfAbsent(identifier, now);
        if (previous == null) {
            bucketCounts[bucketIndex].incrementAndGet();
            return true;
        }
        
        return false;
    }

    /**
     * Add identifier to window without checking duplicates.
     * Use when you know the identifier is unique.
     */
    public void add(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("Identifier cannot be null or empty");
        }
        
        cleanupExpiredBuckets();
        
        long now = System.currentTimeMillis();
        int bucketIndex = getBucketIndex(now);
        
        buckets[bucketIndex].put(identifier, now);
        bucketCounts[bucketIndex].incrementAndGet();
    }

    /**
     * Get current count of unique identifiers in the window.
     */
    public int count() {
        cleanupExpiredBuckets();
        
        int total = 0;
        long now = System.currentTimeMillis();
        long windowStart = now - windowSizeMs;
        
        // Count actual entries within the window across all buckets
        for (int i = 0; i < bucketCount; i++) {
            ConcurrentHashMap<String, Long> bucket = buckets[i];
            for (Long timestamp : bucket.values()) {
                if (timestamp >= windowStart && timestamp <= now) {
                    total++;
                }
            }
        }
        
        return total;
    }

    /**
     * Check if identifier exists in current window.
     */
    public boolean contains(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        return containsInWindow(identifier, System.currentTimeMillis());
    }

    /**
     * Remove an identifier from the window (e.g., when request completes).
     */
    public void remove(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return;
        }
        
        for (int i = 0; i < bucketCount; i++) {
            if (buckets[i].remove(identifier) != null) {
                bucketCounts[i].decrementAndGet();
                return;
            }
        }
    }

    /**
     * Clear all entries from the window.
     */
    public void clear() {
        for (int i = 0; i < bucketCount; i++) {
            buckets[i].clear();
            bucketCounts[i].set(0);
        }
    }

    /**
     * Get the window size in milliseconds.
     */
    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    private boolean containsInWindow(String identifier, long now) {
        long windowStart = now - windowSizeMs;
        
        for (int i = 0; i < bucketCount; i++) {
            Long timestamp = buckets[i].get(identifier);
            if (timestamp != null && timestamp >= windowStart) {
                return true;
            }
        }
        return false;
    }

    private int getBucketIndex(long timestamp) {
        return (int) ((timestamp / bucketSizeMs) % bucketCount);
    }

    private void cleanupExpiredBuckets() {
        long now = System.currentTimeMillis();
        
        // Only cleanup periodically to avoid overhead
        if (now - lastCleanupTime < bucketSizeMs) {
            return;
        }
        
        if (cleanupLock.tryLock()) {
            try {
                long windowStart = now - windowSizeMs;
                
                for (int i = 0; i < bucketCount; i++) {
                    ConcurrentHashMap<String, Long> bucket = buckets[i];
                    
                    // Remove expired entries
                    bucket.entrySet().removeIf(entry -> entry.getValue() < windowStart);
                    
                    // Update count
                    bucketCounts[i].set(bucket.size());
                }
                
                lastCleanupTime = now;
            } finally {
                cleanupLock.unlock();
            }
        }
    }
}
