package com.pool.adapter.executor.tps;

import com.pool.core.TpsCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TpsCounter (invocation-based, no identifier tracking).
 */
class TpsCounterTest {

    private TpsCounter counter;

    @BeforeEach
    void setUp() {
        counter = new TpsCounter(1000);
    }

    @Test
    @DisplayName("Should increment and read count")
    void shouldIncrementAndCount() {
        counter.increment();
        counter.increment();
        counter.increment();

        assertEquals(3, counter.getCount());
    }

    @Test
    @DisplayName("Should check capacity against limit")
    void shouldCheckCapacity() {
        assertTrue(counter.hasCapacity(5));

        counter.increment();
        counter.increment();
        counter.increment();
        counter.increment();
        counter.increment();

        assertFalse(counter.hasCapacity(5));
        assertTrue(counter.hasCapacity(6));
    }

    @Test
    @DisplayName("Unbounded counter always has capacity")
    void unboundedAlwaysHasCapacity() {
        for (int i = 0; i < 1000; i++) {
            counter.increment();
        }
        assertTrue(counter.hasCapacity(0));
        assertTrue(counter.hasCapacity(-1));
    }

    @Test
    @DisplayName("Should reset after window expires")
    void shouldResetAfterWindow() throws InterruptedException {
        TpsCounter shortWindow = new TpsCounter(100);

        shortWindow.increment();
        shortWindow.increment();
        assertEquals(2, shortWindow.getCount());

        Thread.sleep(150);

        assertEquals(0, shortWindow.getCount());
    }

    @Test
    @DisplayName("Should call onReset when window resets with non-zero count")
    void shouldCallOnResetCallback() throws InterruptedException {
        TpsCounter shortWindow = new TpsCounter(100);
        AtomicInteger resetCount = new AtomicInteger(0);
        shortWindow.setOnReset(resetCount::incrementAndGet);

        shortWindow.increment();
        Thread.sleep(150);

        shortWindow.getCount(); // triggers reset
        assertEquals(1, resetCount.get());
    }

    @Test
    @DisplayName("Should not call onReset when window resets with zero count")
    void shouldNotCallOnResetIfEmpty() throws InterruptedException {
        TpsCounter shortWindow = new TpsCounter(100);
        AtomicInteger resetCount = new AtomicInteger(0);
        shortWindow.setOnReset(resetCount::incrementAndGet);

        // Don't increment
        Thread.sleep(150);

        shortWindow.getCount(); // triggers reset check, but count was 0
        assertEquals(0, resetCount.get());
    }

    @Test
    @DisplayName("Should clear counter")
    void shouldClear() {
        counter.increment();
        counter.increment();
        assertEquals(2, counter.getCount());

        counter.clear();
        assertEquals(0, counter.getCount());
    }

    @Test
    @DisplayName("Should throw on invalid window size")
    void shouldThrowOnInvalidWindowSize() {
        assertThrows(IllegalArgumentException.class, () -> new TpsCounter(0));
        assertThrows(IllegalArgumentException.class, () -> new TpsCounter(-100));
    }

    @Test
    @DisplayName("Should return window size")
    void shouldReturnWindowSize() {
        assertEquals(1000, counter.getWindowSizeMs());
    }

    @Test
    @DisplayName("Should handle concurrent increments safely")
    void shouldHandleConcurrentIncrements() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    counter.increment();
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertEquals(threadCount * incrementsPerThread, counter.getCount());
    }

    @Test
    @DisplayName("Sliding window: request admitted after earlier one expires")
    void slidingWindowAllowsAfterExpiry() throws InterruptedException {
        TpsCounter c = new TpsCounter(200); // 200ms window

        c.increment(); // admitted at ~t=0
        assertFalse(c.hasCapacity(1)); // full (1/1)

        Thread.sleep(250); // wait for that entry to expire

        assertTrue(c.hasCapacity(1)); // old entry evicted, 0/1
        c.increment();
        assertEquals(1, c.getCount());
    }

    @Test
    @DisplayName("Sliding window: no false rejection across fixed-window boundary")
    void slidingWindowNoBoundaryFalseRejection() throws InterruptedException {
        // With a fixed window this would fail: both increments land in
        // the same window even though they are < windowSize apart and
        // looking back windowSize from the second there is only 1 entry.
        TpsCounter c = new TpsCounter(300); // 300ms window, TPS=2

        c.increment(); // t ≈ 0
        c.increment(); // t ≈ 0

        assertFalse(c.hasCapacity(2)); // 2/2, full

        Thread.sleep(350); // both entries expire

        // After expiry there should be room again
        assertTrue(c.hasCapacity(2));
        c.increment();
        assertEquals(1, c.getCount());
    }

    @Test
    @DisplayName("Sliding window: partial expiry frees only old entries")
    void slidingWindowPartialExpiry() throws InterruptedException {
        TpsCounter c = new TpsCounter(200); // 200ms window

        c.increment(); // t ≈ 0
        Thread.sleep(120);
        c.increment(); // t ≈ 120ms

        assertEquals(2, c.getCount());

        Thread.sleep(100); // now at ~220ms — first entry (t≈0) expired, second (t≈120) still live

        assertEquals(1, c.getCount());
        assertTrue(c.hasCapacity(2)); // 1 < 2
    }

    @Test
    @DisplayName("Sliding window: rapid burst then gradual recovery")
    void slidingWindowBurstAndRecovery() throws InterruptedException {
        TpsCounter c = new TpsCounter(200); // 200ms window, TPS=3

        c.increment();
        c.increment();
        c.increment();
        assertFalse(c.hasCapacity(3)); // full

        // Wait for all to expire
        Thread.sleep(250);

        assertTrue(c.hasCapacity(3));
        assertEquals(0, c.getCount());
    }
}
