package com.pool.core;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sliding-window-log TPS counter.
 *
 * Maintains a deque of admission timestamps. On every capacity check the
 * deque is drained from the head, removing entries older than
 * {@code windowSizeMs}. The remaining size is the current TPS.
 *
 * This avoids the fixed-window boundary problem where two requests that
 * are less than {@code windowSizeMs} apart could be rejected because they
 * happen to fall inside the same fixed window.
 *
 * Thread-safe — all reads and writes go through a {@link ReentrantLock}.
 */
public class TpsCounter {

    @Getter
    private final long windowSizeMs;
    private final ConcurrentLinkedDeque<Long> timestamps = new ConcurrentLinkedDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * -- SETTER --
     *  Register a callback invoked when stale entries are evicted (capacity freed).
     *  Used to signal drainer threads that queued requests may now be admittable.
     */
    @Setter
    private volatile Runnable onReset;

    public TpsCounter() {
        this(1000);
    }

    public TpsCounter(long windowSizeMs) {
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("Window size must be positive");
        }
        this.windowSizeMs = windowSizeMs;
    }

    /**
     * Check if this counter has capacity for another request.
     *
     * @param maxTps max allowed count per window ({@code <= 0} means unbounded)
     */
    public boolean hasCapacity(int maxTps) {
        if (maxTps <= 0) return true;
        lock.lock();
        try {
            evictStale();
            return timestamps.size() < maxTps;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record an admission. Call after all levels in the chain have been checked.
     */
    public void increment() {
        lock.lock();
        try {
            timestamps.addLast(System.currentTimeMillis());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the number of live (non-expired) admissions in the current window.
     */
    public int getCount() {
        lock.lock();
        try {
            evictStale();
            return timestamps.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clear all entries (for testing).
     */
    public void clear() {
        lock.lock();
        try {
            timestamps.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove timestamps older than {@code now - windowSizeMs} from the head
     * of the deque. Fires the {@code onReset} callback if any were removed.
     * <p>
     * Must be called while holding {@link #lock}.
     */
    private void evictStale() {
        long cutoff = System.currentTimeMillis() - windowSizeMs;
        boolean evicted = false;

        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();
            evicted = true;
        }

        if (evicted) {
            Runnable callback = onReset;
            if (callback != null) {
                callback.run();
            }
        }
    }
}
