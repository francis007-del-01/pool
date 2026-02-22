package com.pool.adapter.executor.tps;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding window counter for tracking TPS using unique request identifiers.
 * Uses a global time-ordered queue for O(1) counting and efficient expiry.
 *
 * <p>Design:
 * <ul>
 *   <li>A {@link ConcurrentLinkedQueue} holds entries in insertion (time) order.</li>
 *   <li>A {@link ConcurrentHashMap} provides O(1) dedup lookups by identifier.</li>
 *   <li>An {@link AtomicInteger} tracks the live count — no scanning required.</li>
 *   <li>Expired entries are drained from the queue head on every mutating operation.</li>
 * </ul>
 */
public class SlidingWindowCounter {

    private final long windowSizeMs;

    // Global time-ordered queue — oldest entries at the head
    private final ConcurrentLinkedQueue<Entry> timeQueue = new ConcurrentLinkedQueue<>();

    // Dedup map: identifier → timestamp (for contains / tryAdd checks)
    private final ConcurrentHashMap<String, Long> identifiers = new ConcurrentHashMap<>();

    // Live count of identifiers currently in the window
    private final AtomicInteger size = new AtomicInteger(0);

    public SlidingWindowCounter() {
        this(1000); // Default 1 second window
    }

    public SlidingWindowCounter(long windowSizeMs) {
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("Window size must be positive");
        }
        this.windowSizeMs = windowSizeMs;
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

        evictExpired();

        long now = System.currentTimeMillis();

        // If identifier already in window, reject
        Long existing = identifiers.get(identifier);
        if (existing != null && existing >= now - windowSizeMs) {
            return false;
        }

        // Atomically insert only if absent
        Long previous = identifiers.putIfAbsent(identifier, now);
        if (previous == null) {
            timeQueue.offer(new Entry(identifier, now));
            size.incrementAndGet();
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

        evictExpired();

        long now = System.currentTimeMillis();
        identifiers.put(identifier, now);
        timeQueue.offer(new Entry(identifier, now));
        size.incrementAndGet();
    }

    /**
     * Get current count of unique identifiers in the window.
     * O(1) — reads the maintained counter after evicting expired entries.
     */
    public int count() {
        evictExpired();
        return size.get();
    }

    /**
     * Check if identifier exists in current window.
     */
    public boolean contains(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        evictExpired();
        Long timestamp = identifiers.get(identifier);
        return timestamp != null && timestamp >= System.currentTimeMillis() - windowSizeMs;
    }

    /**
     * Remove an identifier from the window (e.g., when request completes).
     */
    public void remove(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return;
        }
        if (identifiers.remove(identifier) != null) {
            size.decrementAndGet();
            // Entry remains in timeQueue but will be skipped during eviction
        }
    }

    /**
     * Clear all entries from the window.
     */
    public void clear() {
        timeQueue.clear();
        identifiers.clear();
        size.set(0);
    }

    /**
     * Get the window size in milliseconds.
     */
    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    /**
     * Drain expired entries from the head of the time-ordered queue.
     * Since entries are inserted in time order, we only need to poll from the head
     * until we hit a non-expired entry.
     */
    private void evictExpired() {
        long windowStart = System.currentTimeMillis() - windowSizeMs;

        Entry head;
        while ((head = timeQueue.peek()) != null && head.timestamp < windowStart) {
            // Remove from queue
            Entry polled = timeQueue.poll();
            if (polled == null) {
                break;
            }

            // Only decrement if this entry still owns the slot in the dedup map.
            // It may have been removed already by remove() or replaced by a newer add().
            if (identifiers.remove(polled.identifier, polled.timestamp)) {
                size.decrementAndGet();
            }
        }
    }

    /**
     * Immutable entry in the time-ordered queue.
     */
    private record Entry(String identifier, long timestamp) {}
}
