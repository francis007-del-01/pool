package com.pool.adapter.executor.tps;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SlidingWindowCounter.
 */
class SlidingWindowCounterTest {

    private SlidingWindowCounter counter;

    @BeforeEach
    void setUp() {
        counter = new SlidingWindowCounter(1000); // 1 second window
    }

    @Test
    @DisplayName("Should add unique identifiers and count them")
    void shouldAddAndCount() {
        assertTrue(counter.tryAdd("req-1"));
        assertTrue(counter.tryAdd("req-2"));
        assertTrue(counter.tryAdd("req-3"));

        assertEquals(3, counter.count());
    }

    @Test
    @DisplayName("Should reject duplicate identifiers in same window")
    void shouldRejectDuplicates() {
        assertTrue(counter.tryAdd("req-1"));
        assertFalse(counter.tryAdd("req-1")); // Duplicate

        assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should detect if identifier exists")
    void shouldDetectExistence() {
        counter.add("req-1");

        assertTrue(counter.contains("req-1"));
        assertFalse(counter.contains("req-2"));
    }

    @Test
    @DisplayName("Should remove identifier from window")
    void shouldRemoveIdentifier() {
        counter.add("req-1");
        counter.add("req-2");
        assertEquals(2, counter.count());

        counter.remove("req-1");
        assertEquals(1, counter.count());
        assertFalse(counter.contains("req-1"));
    }

    @Test
    @DisplayName("Should clear all entries")
    void shouldClearAll() {
        counter.add("req-1");
        counter.add("req-2");
        counter.add("req-3");
        assertEquals(3, counter.count());

        counter.clear();
        assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should expire entries after window")
    void shouldExpireAfterWindow() throws InterruptedException {
        SlidingWindowCounter shortWindow = new SlidingWindowCounter(100); // 100ms window
        
        shortWindow.add("req-1");
        assertEquals(1, shortWindow.count());

        // Wait for window to expire
        Thread.sleep(150);

        assertEquals(0, shortWindow.count());
    }

    @Test
    @DisplayName("Should throw on null identifier")
    void shouldThrowOnNullIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> counter.tryAdd(null));
        assertThrows(IllegalArgumentException.class, () -> counter.tryAdd(""));
        assertThrows(IllegalArgumentException.class, () -> counter.add(null));
    }

    @Test
    @DisplayName("Should throw on invalid window size")
    void shouldThrowOnInvalidWindowSize() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounter(0));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounter(-100));
    }

    @Test
    @DisplayName("Should return correct window size")
    void shouldReturnWindowSize() {
        assertEquals(1000, counter.getWindowSizeMs());
    }

    @Test
    @DisplayName("Should handle concurrent additions")
    void shouldHandleConcurrentAdditions() throws InterruptedException {
        int threadCount = 10;
        int additionsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < additionsPerThread; j++) {
                    counter.add("thread-" + threadId + "-req-" + j);
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertEquals(threadCount * additionsPerThread, counter.count());
    }
}
