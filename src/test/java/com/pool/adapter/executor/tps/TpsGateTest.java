package com.pool.adapter.executor.tps;

import com.pool.config.ExecutorSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TpsGate.
 */
class TpsGateTest {

    private ExecutorHierarchy hierarchy;
    private TpsGate gate;

    @BeforeEach
    void setUp() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 10, 100),  // 10 TPS
                ExecutorSpec.child("vip", "main", 5),   // 5 TPS
                ExecutorSpec.child("bulk", "main", 3)   // 3 TPS
        );
        hierarchy = new ExecutorHierarchy(specs);
        gate = new TpsGate(hierarchy, 1000); // 1 second window
    }

    @Test
    @DisplayName("Should acquire when under TPS limit")
    void shouldAcquireUnderLimit() {
        assertTrue(gate.tryAcquire("req-1", "vip"));
        assertTrue(gate.tryAcquire("req-2", "vip"));
        assertTrue(gate.tryAcquire("req-3", "vip"));

        assertEquals(3, gate.getCurrentTps("vip"));
        assertEquals(3, gate.getCurrentTps("main")); // Also counts in parent
    }

    @Test
    @DisplayName("Should reject when executor TPS limit reached")
    void shouldRejectAtExecutorLimit() {
        // Fill up vip's limit (5 TPS)
        for (int i = 1; i <= 5; i++) {
            assertTrue(gate.tryAcquire("req-" + i, "vip"));
        }

        // 6th should fail (vip at limit)
        assertFalse(gate.tryAcquire("req-6", "vip"));
        assertEquals(5, gate.getCurrentTps("vip"));
    }

    @Test
    @DisplayName("Should reject when parent TPS limit reached")
    void shouldRejectAtParentLimit() {
        // Fill up main's limit via vip and bulk
        for (int i = 1; i <= 5; i++) {
            assertTrue(gate.tryAcquire("vip-" + i, "vip"));
        }
        for (int i = 1; i <= 3; i++) {
            assertTrue(gate.tryAcquire("bulk-" + i, "bulk"));
        }
        // Now main has 8/10

        // Add 2 more to main directly
        assertTrue(gate.tryAcquire("main-1", "main"));
        assertTrue(gate.tryAcquire("main-2", "main"));
        // Now main has 10/10

        // Both children should fail (parent at limit)
        assertFalse(gate.tryAcquire("vip-overflow", "vip"));
        assertFalse(gate.tryAcquire("bulk-overflow", "bulk"));
    }

    @Test
    @DisplayName("Should check capacity for executor and ancestors")
    void shouldCheckCapacityWithAncestors() {
        assertTrue(gate.hasCapacityWithAncestors("vip"));

        // Fill main to limit
        for (int i = 1; i <= 10; i++) {
            gate.tryAcquire("main-" + i, "main");
        }

        assertFalse(gate.hasCapacityWithAncestors("vip"));
        assertFalse(gate.hasCapacityWithAncestors("bulk"));
    }

    @Test
    @DisplayName("Should get available capacity")
    void shouldGetAvailableCapacity() {
        assertEquals(5, gate.getAvailableCapacity("vip"));

        gate.tryAcquire("req-1", "vip");
        gate.tryAcquire("req-2", "vip");

        assertEquals(3, gate.getAvailableCapacity("vip"));
    }

    @Test
    @DisplayName("Should release request from counters")
    void shouldReleaseRequest() {
        gate.tryAcquire("req-1", "vip");
        assertEquals(1, gate.getCurrentTps("vip"));

        gate.release("req-1", "vip");
        assertEquals(0, gate.getCurrentTps("vip"));
    }

    @Test
    @DisplayName("Should clear all counters")
    void shouldClearCounters() {
        gate.tryAcquire("req-1", "vip");
        gate.tryAcquire("req-2", "bulk");
        gate.tryAcquire("req-3", "main");

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

        // Fill vip to limit
        for (int i = 1; i <= 5; i++) {
            gate.tryAcquire("vip-" + i, "vip");
        }

        withCapacity = gate.getExecutorsWithCapacity();
        assertEquals(2, withCapacity.size());
        assertFalse(withCapacity.contains("vip"));
    }

    @Test
    @DisplayName("Should throw on null request ID")
    void shouldThrowOnNullRequestId() {
        assertThrows(IllegalArgumentException.class, 
                () -> gate.tryAcquire((String) null, "vip"));
        assertThrows(IllegalArgumentException.class, 
                () -> gate.tryAcquire("", "vip"));
    }

    @Test
    @DisplayName("Should throw on null executor ID")
    void shouldThrowOnNullExecutorId() {
        assertThrows(IllegalArgumentException.class, 
                () -> gate.tryAcquire("req-1", null));
        assertThrows(IllegalArgumentException.class, 
                () -> gate.tryAcquire("req-1", ""));
    }

    @Test
    @DisplayName("Should handle unbounded executor")
    void shouldHandleUnboundedExecutor() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.unboundedRoot("main", 100) // TPS = 0 (unbounded)
        );
        ExecutorHierarchy unboundedHierarchy = new ExecutorHierarchy(specs);
        TpsGate unboundedGate = new TpsGate(unboundedHierarchy);

        // Should always have capacity
        for (int i = 1; i <= 1000; i++) {
            assertTrue(unboundedGate.tryAcquire("req-" + i, "main"));
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
    @DisplayName("Should use per-executor identifier field from TaskContext")
    void shouldUsePerExecutorIdentifierField() {
        // Create hierarchy with different identifier fields per executor
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 10, 100, "$req.requestId"),  // Count by requestId
                ExecutorSpec.child("ips", "main", 3, "$req.ipAddress") // Count by ipAddress
        );
        ExecutorHierarchy customHierarchy = new ExecutorHierarchy(specs);
        TpsGate customGate = new TpsGate(customHierarchy, 1000);

        // Create TaskContext with both fields
        com.pool.core.TaskContext ctx1 = com.pool.core.TaskContext.builder()
                .taskId("task-1")
                .requestVariable("requestId", "req-001")
                .requestVariable("ipAddress", "192.168.1.1")
                .build();

        com.pool.core.TaskContext ctx2 = com.pool.core.TaskContext.builder()
                .taskId("task-2")
                .requestVariable("requestId", "req-002")
                .requestVariable("ipAddress", "192.168.1.1") // Same IP
                .build();

        // First request should succeed
        assertTrue(customGate.tryAcquire(ctx1, "ips"));
        assertEquals(1, customGate.getCurrentTps("ips"));

        // Second request with same IP should be allowed (IP already in window)
        // but won't increment ips counter since IP is duplicate
        assertTrue(customGate.tryAcquire(ctx2, "ips"));
        
        // main counts by requestId: 2 unique (req-001, req-002)
        assertEquals(2, customGate.getCurrentTps("main"));
        
        // ips counts by ipAddress: only 1 unique IP (192.168.1.1)
        assertEquals(1, customGate.getCurrentTps("ips"));
    }

    @Test
    @DisplayName("Should allow duplicate identifiers without incrementing count")
    void shouldAllowDuplicateIdentifiersWithoutIncrement() {
        // bulk has TPS=3, means max 3 unique identifiers per second
        // Same identifier should be allowed multiple times without counting again
        
        // Verify TPS limit for bulk
        assertEquals(3, gate.getHierarchy().getTps("bulk"));
        
        // First 3 unique identifiers should be allowed
        assertTrue(gate.tryAcquire("req-1", "bulk"));
        assertEquals(1, gate.getCurrentTps("bulk"));
        assertTrue(gate.hasCapacity("bulk")); // 1 < 3
        
        assertTrue(gate.tryAcquire("req-2", "bulk"));
        assertEquals(2, gate.getCurrentTps("bulk"));
        assertTrue(gate.hasCapacity("bulk")); // 2 < 3
        
        assertTrue(gate.tryAcquire("req-3", "bulk"));
        assertEquals(3, gate.getCurrentTps("bulk"));
        assertFalse(gate.hasCapacity("bulk")); // 3 < 3 = false
        
        // Fourth unique should be rejected (TPS limit = 3)
        assertFalse(gate.tryAcquire("req-4", "bulk"));
        assertEquals(3, gate.getCurrentTps("bulk")); // Still 3
        
        // But existing identifiers should still be allowed (no new slot needed)
        assertTrue(gate.tryAcquire("req-1", "bulk"));
        assertTrue(gate.tryAcquire("req-2", "bulk"));
        assertTrue(gate.tryAcquire("req-3", "bulk"));
        assertEquals(3, gate.getCurrentTps("bulk")); // Still 3, no increment
    }

    @Test
    @DisplayName("Should fallback to taskId when identifier field not configured")
    void shouldFallbackToTaskId() {
        // Executor without identifier field configured
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 10, 100) // No identifier field
        );
        ExecutorHierarchy noFieldHierarchy = new ExecutorHierarchy(specs);
        TpsGate noFieldGate = new TpsGate(noFieldHierarchy, 1000);

        com.pool.core.TaskContext ctx = com.pool.core.TaskContext.builder()
                .taskId("my-task-id")
                .requestVariable("someField", "someValue")
                .build();

        assertTrue(noFieldGate.tryAcquire(ctx, "main"));
        assertEquals(1, noFieldGate.getCurrentTps("main"));
    }
}
