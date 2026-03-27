package com.pool.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TpsContext — InheritableThreadLocal bypass mechanism.
 */
class TpsContextTest {

    @AfterEach
    void tearDown() {
        TpsContext.clear();
    }

    @Test
    @DisplayName("Default state should be not processed")
    void defaultNotProcessed() {
        assertFalse(TpsContext.isProcessed());
    }

    @Test
    @DisplayName("markProcessed should set the flag")
    void markProcessedSetsFlag() {
        TpsContext.markProcessed();
        assertTrue(TpsContext.isProcessed());
    }

    @Test
    @DisplayName("clear should reset the flag")
    void clearResetsFlag() {
        TpsContext.markProcessed();
        assertTrue(TpsContext.isProcessed());

        TpsContext.clear();
        assertFalse(TpsContext.isProcessed());
    }

    @Test
    @DisplayName("Child thread should inherit processed state")
    void childThreadInherits() throws InterruptedException {
        TpsContext.markProcessed();

        AtomicBoolean childSaw = new AtomicBoolean(false);
        Thread child = new Thread(() -> childSaw.set(TpsContext.isProcessed()));
        child.start();
        child.join();

        assertTrue(childSaw.get());
    }

    @Test
    @DisplayName("Separate thread should not see parent state if parent unset")
    void separateThreadIsolated() throws InterruptedException {
        assertFalse(TpsContext.isProcessed());

        AtomicBoolean childSaw = new AtomicBoolean(true);
        Thread child = new Thread(() -> childSaw.set(TpsContext.isProcessed()));
        child.start();
        child.join();

        assertFalse(childSaw.get());
    }
}
