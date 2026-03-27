package com.pool.adapter.executor.tps;

import com.pool.config.ExecutorHierarchy;
import com.pool.config.ExecutorSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TpsGate with TpsCounter-based admission (no identifier tracking).
 */
class TpsGateTest {

    private ExecutorHierarchy hierarchy;
    private TpsGate gate;

    @BeforeEach
    void setUp() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 10, 100),
                ExecutorSpec.child("vip", "main", 5),
                ExecutorSpec.child("bulk", "main", 3)
        );
        hierarchy = new ExecutorHierarchy(specs);
        gate = new TpsGate(hierarchy, 1000);
    }

    @Test
    @DisplayName("Should acquire when under TPS limit")
    void shouldAcquireUnderLimit() {
        assertTrue(gate.tryAcquire("vip"));
        assertTrue(gate.tryAcquire("vip"));
        assertTrue(gate.tryAcquire("vip"));

        assertEquals(3, gate.getCurrentTps("vip"));
        assertEquals(3, gate.getCurrentTps("main"));
    }

    @Test
    @DisplayName("Should reject when executor TPS limit reached")
    void shouldRejectAtExecutorLimit() {
        for (int i = 0; i < 5; i++) {
            assertTrue(gate.tryAcquire("vip"));
        }

        assertFalse(gate.tryAcquire("vip"));
        assertEquals(5, gate.getCurrentTps("vip"));
    }

    @Test
    @DisplayName("Should reject when parent TPS limit reached")
    void shouldRejectAtParentLimit() {
        for (int i = 0; i < 5; i++) {
            assertTrue(gate.tryAcquire("vip"));
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(gate.tryAcquire("bulk"));
        }
        // main has 8/10

        assertTrue(gate.tryAcquire("main"));
        assertTrue(gate.tryAcquire("main"));
        // main has 10/10

        assertFalse(gate.tryAcquire("vip"));
        assertFalse(gate.tryAcquire("bulk"));
    }

    @Test
    @DisplayName("Should check capacity for executor and ancestors")
    void shouldCheckCapacityWithAncestors() {
        assertTrue(gate.hasCapacityWithAncestors("vip"));

        for (int i = 0; i < 10; i++) {
            gate.tryAcquire("main");
        }

        assertFalse(gate.hasCapacityWithAncestors("vip"));
        assertFalse(gate.hasCapacityWithAncestors("bulk"));
    }

    @Test
    @DisplayName("Should get available capacity")
    void shouldGetAvailableCapacity() {
        assertEquals(5, gate.getAvailableCapacity("vip"));

        gate.tryAcquire("vip");
        gate.tryAcquire("vip");

        assertEquals(3, gate.getAvailableCapacity("vip"));
    }

    @Test
    @DisplayName("Should clear all counters")
    void shouldClearCounters() {
        gate.tryAcquire("vip");
        gate.tryAcquire("bulk");
        gate.tryAcquire("main");

        gate.clear();

        assertEquals(0, gate.getCurrentTps("vip"));
        assertEquals(0, gate.getCurrentTps("bulk"));
        assertEquals(0, gate.getCurrentTps("main"));
    }

    @Test
    @DisplayName("Should get executors with capacity")
    void shouldGetExecutorsWithCapacity() {
        List<String> withCapacity = gate.getExecutorsWithCapacity();
        assertEquals(3, withCapacity.size());

        for (int i = 0; i < 5; i++) {
            gate.tryAcquire("vip");
        }

        withCapacity = gate.getExecutorsWithCapacity();
        assertEquals(2, withCapacity.size());
        assertFalse(withCapacity.contains("vip"));
    }

    @Test
    @DisplayName("Should throw on null executor ID")
    void shouldThrowOnNullExecutorId() {
        assertThrows(IllegalArgumentException.class, () -> gate.tryAcquire(null));
        assertThrows(IllegalArgumentException.class, () -> gate.tryAcquire(""));
    }

    @Test
    @DisplayName("Should handle unbounded executor")
    void shouldHandleUnboundedExecutor() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.unboundedRoot("main", 100)
        );
        ExecutorHierarchy unboundedHierarchy = new ExecutorHierarchy(specs);
        TpsGate unboundedGate = new TpsGate(unboundedHierarchy);

        for (int i = 0; i < 1000; i++) {
            assertTrue(unboundedGate.tryAcquire("main"));
        }

        assertEquals(Integer.MAX_VALUE, unboundedGate.getAvailableCapacity("main"));
    }

    @Test
    @DisplayName("Should return hierarchy and window size")
    void shouldReturnMetadata() {
        assertSame(hierarchy, gate.getHierarchy());
        assertEquals(1000, gate.getWindowSizeMs());
    }

    @Test
    @DisplayName("Every invocation increments the counter")
    void everyInvocationIncrements() {
        gate.tryAcquire("bulk");
        assertEquals(1, gate.getCurrentTps("bulk"));

        gate.tryAcquire("bulk");
        assertEquals(2, gate.getCurrentTps("bulk"));

        gate.tryAcquire("bulk");
        assertEquals(3, gate.getCurrentTps("bulk"));

        // 4th should be rejected
        assertFalse(gate.tryAcquire("bulk"));
        assertEquals(3, gate.getCurrentTps("bulk"));
    }

    @Test
    @DisplayName("Child consumption counts against parent budget")
    void childCountsAgainstParent() {
        for (int i = 0; i < 5; i++) {
            gate.tryAcquire("vip");
        }
        assertEquals(5, gate.getCurrentTps("main"));
        assertEquals(5, gate.getAvailableCapacity("main"));

        for (int i = 0; i < 3; i++) {
            gate.tryAcquire("bulk");
        }
        assertEquals(8, gate.getCurrentTps("main"));
        assertEquals(2, gate.getAvailableCapacity("main"));
    }
}
