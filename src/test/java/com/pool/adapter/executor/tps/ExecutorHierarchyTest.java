package com.pool.adapter.executor.tps;

import com.pool.config.ExecutorSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExecutorHierarchy.
 */
class ExecutorHierarchyTest {

    @Test
    @DisplayName("Should build hierarchy with root and children")
    void shouldBuildHierarchy() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("vip", "main", 400),
                ExecutorSpec.child("bulk", "main", 200)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);

        assertEquals("main", hierarchy.getRootId());
        assertEquals(3, hierarchy.getAllExecutorIds().size());
        assertTrue(hierarchy.isRoot("main"));
        assertFalse(hierarchy.isRoot("vip"));
    }

    @Test
    @DisplayName("Should get executor chain from leaf to root")
    void shouldGetExecutorChain() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("vip", "main", 400)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);

        List<String> chain = hierarchy.getExecutorChain("vip");
        assertEquals(2, chain.size());
        assertEquals("vip", chain.get(0));  // Self first
        assertEquals("main", chain.get(1)); // Then parent
    }

    @Test
    @DisplayName("Should get TPS for executor")
    void shouldGetTps() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("vip", "main", 400)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);

        assertEquals(1000, hierarchy.getTps("main"));
        assertEquals(400, hierarchy.getTps("vip"));
    }

    @Test
    @DisplayName("Should get queue capacity from root")
    void shouldGetQueueCapacity() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("vip", "main", 400)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);

        assertEquals(5000, hierarchy.getQueueCapacity("main"));
    }

    @Test
    @DisplayName("Should get children of executor")
    void shouldGetChildren() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("vip", "main", 400),
                ExecutorSpec.child("bulk", "main", 200)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);

        List<String> children = hierarchy.getChildren("main");
        assertEquals(2, children.size());
        assertTrue(children.contains("vip"));
        assertTrue(children.contains("bulk"));
    }

    @Test
    @DisplayName("Should get parent of executor")
    void shouldGetParent() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("vip", "main", 400)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);

        assertEquals("main", hierarchy.getParent("vip"));
        assertNull(hierarchy.getParent("main"));
    }

    @Test
    @DisplayName("Should get depth of executor")
    void shouldGetDepth() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("level1", "main", 500),
                ExecutorSpec.child("level2", "level1", 200)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);

        assertEquals(0, hierarchy.getDepth("main"));
        assertEquals(1, hierarchy.getDepth("level1"));
        assertEquals(2, hierarchy.getDepth("level2"));
    }

    @Test
    @DisplayName("Should get leaf executors")
    void shouldGetLeafExecutors() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("vip", "main", 400),
                ExecutorSpec.child("bulk", "main", 200)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);

        List<String> leaves = hierarchy.getLeafExecutors();
        assertEquals(2, leaves.size());
        assertTrue(leaves.contains("vip"));
        assertTrue(leaves.contains("bulk"));
    }

    @Test
    @DisplayName("Should reject null or empty specs")
    void shouldRejectNullSpecs() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutorHierarchy(null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutorHierarchy(List.of()));
    }

    @Test
    @DisplayName("Should reject duplicate executor IDs")
    void shouldRejectDuplicateIds() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                new ExecutorSpec("main", null, 500, 0, null) // Duplicate
        );

        assertThrows(IllegalArgumentException.class, () -> new ExecutorHierarchy(specs));
    }

    @Test
    @DisplayName("Should reject multiple root executors")
    void shouldRejectMultipleRoots() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main1", 1000, 5000),
                ExecutorSpec.root("main2", 500, 2000) // Second root
        );

        assertThrows(IllegalArgumentException.class, () -> new ExecutorHierarchy(specs));
    }

    @Test
    @DisplayName("Should reject unknown parent reference")
    void shouldRejectUnknownParent() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("vip", "unknown", 400) // Unknown parent
        );

        assertThrows(IllegalArgumentException.class, () -> new ExecutorHierarchy(specs));
    }

    @Test
    @DisplayName("Should reject child TPS exceeding parent TPS")
    void shouldRejectChildExceedingParent() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.root("main", 100, 5000),
                ExecutorSpec.child("vip", "main", 200) // Exceeds parent
        );

        assertThrows(IllegalArgumentException.class, () -> new ExecutorHierarchy(specs));
    }

    @Test
    @DisplayName("Should allow unbounded parent with bounded child")
    void shouldAllowUnboundedParent() {
        List<ExecutorSpec> specs = List.of(
                ExecutorSpec.unboundedRoot("main", 5000), // TPS = 0 (unbounded)
                ExecutorSpec.child("vip", "main", 400)
        );

        ExecutorHierarchy hierarchy = new ExecutorHierarchy(specs);
        assertEquals(0, hierarchy.getTps("main"));
        assertEquals(400, hierarchy.getTps("vip"));
    }
}
