package com.pool;

import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.config.*;
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;
import com.pool.exception.TaskRejectedException;
import com.pool.policy.EvaluationResult;
import com.pool.policy.PolicyEngine;
import com.pool.policy.PolicyEngineFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Pool application functionality.
 * Tests cover:
 * - Region-based routing (NORTH_AMERICA, EUROPE, DEFAULT)
 * - Customer tier routing (PLATINUM, GOLD, DEFAULT)
 * - Transaction amount thresholds
 * - Executor assignment (fast vs bulk)
 * - Concurrent task submission
 * - TPS limiting behavior
 * - Statistics tracking
 */
class PoolApplicationTest {

    private TpsPoolExecutor executor;
    private PoolConfig config;
    private PolicyEngine policyEngine;

    @BeforeEach
    void setUp() {
        config = createPoolConfig();
        executor = new TpsPoolExecutor(config);
        policyEngine = PolicyEngineFactory.create(config);
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    // =====================================================================
    // Region Routing Tests
    // =====================================================================

    @Test
    @DisplayName("NORTH_AMERICA GOLD customer routes to fast executor")
    void northAmericaGoldRoutesToFast() {
        TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 5000, 1);
        EvaluationResult result = policyEngine.evaluate(ctx);

        assertEquals("fast", result.getMatchedPath().executor());
        assertNotNull(result.getPriorityKey());
    }

    @Test
    @DisplayName("NORTH_AMERICA PLATINUM high-value routes to fast executor")
    void northAmericaPlatinumHighValueRoutesToFast() {
        TaskContext ctx = createContext("NORTH_AMERICA", "PLATINUM", 150000, 10);
        EvaluationResult result = policyEngine.evaluate(ctx);

        assertEquals("fast", result.getMatchedPath().executor());
    }

    @Test
    @DisplayName("NORTH_AMERICA PLATINUM low-value routes to fast executor")
    void northAmericaPlatinumLowValueRoutesToFast() {
        TaskContext ctx = createContext("NORTH_AMERICA", "PLATINUM", 5000, 5);
        EvaluationResult result = policyEngine.evaluate(ctx);

        assertEquals("fast", result.getMatchedPath().executor());
    }

    @Test
    @DisplayName("NORTH_AMERICA default tier routes to bulk executor")
    void northAmericaDefaultTierRoutesToBulk() {
        TaskContext ctx = createContext("NORTH_AMERICA", "STANDARD", 1000, 1);
        EvaluationResult result = policyEngine.evaluate(ctx);

        assertEquals("bulk", result.getMatchedPath().executor());
    }

    @Test
    @DisplayName("EUROPE region routes to fast executor")
    void europeRoutesToFast() {
        TaskContext ctx = createContext("EUROPE", "STANDARD", 1000, 1);
        EvaluationResult result = policyEngine.evaluate(ctx);

        assertEquals("fast", result.getMatchedPath().executor());
    }

    @Test
    @DisplayName("Unknown region routes to bulk executor (default)")
    void unknownRegionRoutesToBulk() {
        TaskContext ctx = createContext("ASIA", "GOLD", 5000, 5);
        EvaluationResult result = policyEngine.evaluate(ctx);

        assertEquals("bulk", result.getMatchedPath().executor());
    }

    // =====================================================================
    // Customer Tier Tests
    // =====================================================================

    @ParameterizedTest
    @DisplayName("Customer tier routing for NORTH_AMERICA")
    @CsvSource({
            "PLATINUM, 150000, fast",
            "PLATINUM, 50000, fast",
            "GOLD, 10000, fast",
            "SILVER, 10000, bulk",
            "STANDARD, 10000, bulk"
    })
    void customerTierRoutingNorthAmerica(String tier, int amount, String expectedExecutor) {
        TaskContext ctx = createContext("NORTH_AMERICA", tier, amount, 1);
        EvaluationResult result = policyEngine.evaluate(ctx);

        assertEquals(expectedExecutor, result.getMatchedPath().executor());
    }

    // =====================================================================
    // Task Submission Tests
    // =====================================================================

    @Test
    @DisplayName("Submit and execute callable task")
    void submitAndExecuteCallable() throws Exception {
        TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 5000, 1);

        Future<String> future = executor.submit(ctx, () -> {
            Thread.sleep(50);
            return "completed";
        });

        assertEquals("completed", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Submit and execute runnable task")
    void submitAndExecuteRunnable() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 5000, 1);

        executor.submit(ctx, counter::incrementAndGet);

        Thread.sleep(200);
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("Submit multiple tasks from different regions")
    void submitMultipleRegions() throws Exception {
        List<Future<String>> futures = new ArrayList<>();

        // Submit tasks from different regions
        String[] regions = {"NORTH_AMERICA", "EUROPE", "ASIA", "NORTH_AMERICA"};
        String[] tiers = {"PLATINUM", "GOLD", "STANDARD", "GOLD"};

        for (int i = 0; i < regions.length; i++) {
            TaskContext ctx = createContext(regions[i], tiers[i], 1000 * (i + 1), i);
            int idx = i;
            futures.add(executor.submit(ctx, () -> "Task-" + idx));
        }

        // Verify all complete
        for (int i = 0; i < futures.size(); i++) {
            assertEquals("Task-" + i, futures.get(i).get(5, TimeUnit.SECONDS));
        }
    }

    // =====================================================================
    // Concurrent Submission Tests
    // =====================================================================

    @Test
    @DisplayName("Handle concurrent task submissions")
    void handleConcurrentSubmissions() throws Exception {
        int taskCount = 50;
        AtomicInteger completedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(taskCount);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 1000, i);
            int taskNum = i;
            futures.add(executor.submit(ctx, () -> {
                Thread.sleep(10);
                completedCount.incrementAndGet();
                latch.countDown();
                return taskNum;
            }));
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete");
        assertEquals(taskCount, completedCount.get());
    }

    @Test
    @DisplayName("Mixed tier concurrent submissions")
    void mixedTierConcurrentSubmissions() throws Exception {
        int tasksPerTier = 10;
        String[] tiers = {"PLATINUM", "GOLD", "STANDARD"};
        AtomicInteger completedCount = new AtomicInteger(0);
        List<Future<String>> futures = new ArrayList<>();

        for (String tier : tiers) {
            for (int i = 0; i < tasksPerTier; i++) {
                TaskContext ctx = createContext("NORTH_AMERICA", tier, 1000, i);
                futures.add(executor.submit(ctx, () -> {
                    completedCount.incrementAndGet();
                    return tier;
                }));
            }
        }

        // Wait for all to complete
        for (Future<String> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        assertEquals(tasksPerTier * tiers.length, completedCount.get());
    }

    // =====================================================================
    // Statistics Tests
    // =====================================================================

    @Test
    @DisplayName("Track submission statistics")
    void trackSubmissionStats() throws Exception {
        int taskCount = 5;
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 1000, i);
            futures.add(executor.submit(ctx, () -> 1));
        }

        // Wait for completion
        for (Future<Integer> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        TpsPoolExecutor.TpsExecutorStats stats = executor.getStats();
        assertEquals(taskCount, stats.submitted());
        assertEquals(taskCount, stats.executed());
        assertEquals(0, stats.rejected());
    }

    @Test
    @DisplayName("Get executor hierarchy")
    void getExecutorHierarchy() {
        assertNotNull(executor.getHierarchy());
        assertEquals("main", executor.getHierarchy().getRootId());
        assertTrue(executor.getHierarchy().getAllExecutorIds().contains("fast"));
        assertTrue(executor.getHierarchy().getAllExecutorIds().contains("bulk"));
    }

    @Test
    @DisplayName("Get available TPS for executors")
    void getAvailableTps() {
        assertTrue(executor.getAvailableTps("main") > 0);
        assertTrue(executor.getAvailableTps("fast") > 0);
        assertTrue(executor.getAvailableTps("bulk") > 0);
    }

    // =====================================================================
    // Error Handling Tests
    // =====================================================================

    @Test
    @DisplayName("Reject null context")
    void rejectNullContext() {
        assertThrows(NullPointerException.class, () -> executor.submit(null, () -> {}));
    }

    @Test
    @DisplayName("Reject null runnable task")
    void rejectNullRunnable() {
        TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 1000, 1);
        assertThrows(NullPointerException.class, () -> executor.submit(ctx, (Runnable) null));
    }

    @Test
    @DisplayName("Reject task after shutdown")
    void rejectAfterShutdown() {
        executor.shutdown();

        TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 1000, 1);
        assertThrows(TaskRejectedException.class, () -> executor.submit(ctx, () -> {}));
    }

    // =====================================================================
    // Shutdown Tests
    // =====================================================================

    @Test
    @DisplayName("Shutdown gracefully")
    void shutdownGracefully() throws Exception {
        // Submit a task
        TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 1000, 1);
        Future<String> future = executor.submit(ctx, () -> "done");

        future.get(5, TimeUnit.SECONDS);

        executor.shutdown();
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(executor.isTerminated());
    }

    @Test
    @DisplayName("Shutdown immediately")
    void shutdownImmediately() {
        executor.shutdownNow();
        assertTrue(executor.isShutdown());
    }

    // =====================================================================
    // Priority Path Tests
    // =====================================================================

    @Test
    @DisplayName("Verify matched path contains correct node names")
    void verifyMatchedPathNodeNames() {
        TaskContext ctx = createContext("NORTH_AMERICA", "PLATINUM", 150000, 10);
        EvaluationResult result = policyEngine.evaluate(ctx);

        assertNotNull(result.getMatchedPath());
        String pathStr = result.getMatchedPath().toPathString();
        assertTrue(pathStr.contains("L1.NORTH_AMERICA"));
        assertTrue(pathStr.contains("L2.PLATINUM"));
        assertTrue(pathStr.contains("L3.HIGH_VALUE"));
    }

    @Test
    @DisplayName("Verify GOLD path for NORTH_AMERICA")
    void verifyGoldPath() {
        TaskContext ctx = createContext("NORTH_AMERICA", "GOLD", 5000, 1);
        EvaluationResult result = policyEngine.evaluate(ctx);

        String pathStr = result.getMatchedPath().toPathString();
        assertTrue(pathStr.contains("L1.NORTH_AMERICA"));
        assertTrue(pathStr.contains("L2.GOLD"));
    }

    @Test
    @DisplayName("Verify DEFAULT path for unknown tier")
    void verifyDefaultPathForUnknownTier() {
        TaskContext ctx = createContext("NORTH_AMERICA", "BRONZE", 5000, 1);
        EvaluationResult result = policyEngine.evaluate(ctx);

        String pathStr = result.getMatchedPath().toPathString();
        assertTrue(pathStr.contains("L1.NORTH_AMERICA"));
        assertTrue(pathStr.contains("L2.DEFAULT"));
        assertEquals("bulk", result.getMatchedPath().executor());
    }

    // =====================================================================
    // Queue and TPS Tests
    // =====================================================================

    @Test
    @DisplayName("Queue size starts at zero")
    void queueSizeStartsAtZero() {
        assertEquals(0, executor.getQueueSize());
        assertEquals(0, executor.getQueueSize("main"));
        assertEquals(0, executor.getQueueSize("fast"));
        assertEquals(0, executor.getQueueSize("bulk"));
    }

    @Test
    @DisplayName("Active count starts at zero")
    void activeCountStartsAtZero() {
        assertEquals(0, executor.getActiveCount());
    }

    @Test
    @DisplayName("TPS gate is accessible")
    void tpsGateAccessible() {
        assertNotNull(executor.getTpsGate());
    }

    // =====================================================================
    // TaskContext Factory Tests
    // =====================================================================

    @Test
    @DisplayName("Create TaskContext from JSON")
    void createTaskContextFromJson() {
        String json = """
            {
                "region": "NORTH_AMERICA",
                "customerTier": "GOLD",
                "transactionAmount": 5000
            }
            """;

        TaskContext ctx = TaskContextFactory.create(json, Map.of("clientId", "test"));

        assertEquals("NORTH_AMERICA", ctx.getRequestVariable("region").orElse(null));
        assertEquals("GOLD", ctx.getRequestVariable("customerTier").orElse(null));
        assertEquals(5000, ctx.getRequestVariable("transactionAmount").orElse(null));
        assertEquals("test", ctx.getContextVariable("clientId").orElse(null));
    }

    @Test
    @DisplayName("TaskContext has system variables")
    void taskContextHasSystemVariables() {
        String json = "{\"test\": \"value\"}";
        TaskContext ctx = TaskContextFactory.create(json, null);

        assertNotNull(ctx.getTaskId());
        assertTrue(ctx.getSubmittedAt() > 0);
    }

    // =====================================================================
    // Simulation: Real-world scenario
    // =====================================================================

    @Test
    @DisplayName("Simulate mixed workload similar to PoolApplication demo")
    void simulateMixedWorkload() throws Exception {
        int taskCount = 20;
        List<Future<String>> futures = new ArrayList<>();
        Map<String, String> context = Map.of("clientId", "test-app");

        // Simulate the demo from PoolApplication
        for (int i = 0; i < taskCount; i++) {
            String json = """
                {
                    "region": "NORTH_AMERICA",
                    "customerTier": "GOLD",
                    "taskNumber": %d
                }
                """.formatted(i);

            TaskContext ctx = TaskContextFactory.create(json, context);
            int taskNum = i;
            futures.add(executor.submit(ctx, () -> {
                Thread.sleep(50);
                return "Task " + taskNum + " completed";
            }));
        }

        // Verify all tasks complete
        for (int i = 0; i < futures.size(); i++) {
            String result = futures.get(i).get(10, TimeUnit.SECONDS);
            assertEquals("Task " + i + " completed", result);
        }

        // Verify stats
        TpsPoolExecutor.TpsExecutorStats stats = executor.getStats();
        assertTrue(stats.submitted() >= taskCount);
        assertTrue(stats.executed() >= taskCount);
    }

    // =====================================================================
    // Helper Methods
    // =====================================================================

    private TaskContext createContext(String region, String tier, int amount, int priority) {
        String json = """
            {
                "region": "%s",
                "customerTier": "%s",
                "transactionAmount": %d,
                "priority": %d,
                "urgency": %d
            }
            """.formatted(region, tier, amount, priority, priority);

        return TaskContextFactory.create(json, Map.of("clientId", "test"));
    }

    private PoolConfig createPoolConfig() {
        // Create executors matching pool.yaml
        List<ExecutorSpec> executors = List.of(
                ExecutorSpec.root("main", 1000, 5000, "$req.requestId"),
                new ExecutorSpec("fast", "main", 500, 2000, "$req.requestId"),
                new ExecutorSpec("bulk", "main", 300, 1000, "$req.requestId")
        );

        // Create priority tree matching pool.yaml
        List<PriorityNodeConfig> priorityTree = List.of(
                // L1.NORTH_AMERICA
                new PriorityNodeConfig(
                        "L1.NORTH_AMERICA",
                        "$req.region == \"NORTH_AMERICA\"",
                        List.of(
                                // L2.PLATINUM
                                new PriorityNodeConfig(
                                        "L2.PLATINUM",
                                        "$req.customerTier == \"PLATINUM\"",
                                        List.of(
                                                new PriorityNodeConfig(
                                                        "L3.HIGH_VALUE",
                                                        "$req.transactionAmount > 100000",
                                                        null,
                                                        new SortByConfig("$req.priority", SortDirection.DESC),
                                                        "fast"
                                                ),
                                                new PriorityNodeConfig(
                                                        "L3.DEFAULT",
                                                        "true",
                                                        null,
                                                        new SortByConfig("$req.urgency", SortDirection.DESC),
                                                        "fast"
                                                )
                                        ),
                                        null,
                                        null
                                ),
                                // L2.GOLD
                                new PriorityNodeConfig(
                                        "L2.GOLD",
                                        "$req.customerTier == \"GOLD\"",
                                        List.of(
                                                new PriorityNodeConfig(
                                                        "L3.DEFAULT",
                                                        "true",
                                                        null,
                                                        SortByConfig.fifo(),
                                                        "fast"
                                                )
                                        ),
                                        null,
                                        null
                                ),
                                // L2.DEFAULT
                                new PriorityNodeConfig(
                                        "L2.DEFAULT",
                                        "true",
                                        List.of(
                                                new PriorityNodeConfig(
                                                        "L3.DEFAULT",
                                                        "true",
                                                        null,
                                                        SortByConfig.fifo(),
                                                        "bulk"
                                                )
                                        ),
                                        null,
                                        null
                                )
                        ),
                        null,
                        null
                ),
                // L1.EUROPE
                new PriorityNodeConfig(
                        "L1.EUROPE",
                        "$req.region == \"EUROPE\"",
                        List.of(
                                new PriorityNodeConfig(
                                        "L2.DEFAULT",
                                        "true",
                                        List.of(
                                                new PriorityNodeConfig(
                                                        "L3.DEFAULT",
                                                        "true",
                                                        null,
                                                        new SortByConfig("$req.priority", SortDirection.DESC),
                                                        "fast"
                                                )
                                        ),
                                        null,
                                        null
                                )
                        ),
                        null,
                        null
                ),
                // L1.DEFAULT
                new PriorityNodeConfig(
                        "L1.DEFAULT",
                        "true",
                        List.of(
                                new PriorityNodeConfig(
                                        "L2.DEFAULT",
                                        "true",
                                        List.of(
                                                new PriorityNodeConfig(
                                                        "L3.DEFAULT",
                                                        "true",
                                                        null,
                                                        SortByConfig.fifo(),
                                                        "bulk"
                                                )
                                        ),
                                        null,
                                        null
                                )
                        ),
                        null,
                        null
                )
        );

        return new PoolConfig(
                "test-pool",
                "1.0",
                executors,
                priorityTree,
                StrategyConfig.fifo()
        );
    }
}
