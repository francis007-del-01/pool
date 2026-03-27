package com.pool;

import com.pool.adapter.executor.tps.TaskQueueManager;
import com.pool.adapter.executor.tps.TpsGate;
import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.config.*;
import com.pool.core.TpsCounter;
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
import java.util.concurrent.*;
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
        policyEngine = PolicyEngineFactory.create(config);
        executor = buildExecutor(config, policyEngine);
    }

    private static TpsPoolExecutor buildExecutor(PoolConfig config, PolicyEngine policyEngine) {
        ExecutorHierarchy hierarchy = new ExecutorHierarchy(config.getExecutors());
        TpsGate tpsGate = new TpsGate(hierarchy);
        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("test-pool-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        TaskQueueManager queueManager = buildQueueManager(hierarchy, tpsGate, threadPool);
        return new TpsPoolExecutor(config, policyEngine, hierarchy, tpsGate, queueManager);
    }

    private static TaskQueueManager buildQueueManager(ExecutorHierarchy hierarchy, TpsGate tpsGate, ExecutorService threadPool) {
        Map<String, java.util.concurrent.locks.ReentrantLock> locks = new ConcurrentHashMap<>();
        Map<String, java.util.concurrent.locks.Condition> conditions = new ConcurrentHashMap<>();
        Map<String, com.pool.strategy.PriorityStrategy<TaskQueueManager.QueuedTask>> strategies = new ConcurrentHashMap<>();

        for (String id : hierarchy.getAllExecutorIds()) {
            java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
            locks.put(id, lock);
            conditions.put(id, lock.newCondition());

            int cap = hierarchy.getQueueCapacity(id);
            strategies.put(id, com.pool.strategy.PriorityStrategyFactory.createDefault(cap <= 0 ? Integer.MAX_VALUE : cap));

            TpsCounter counter = tpsGate.getCounter(id);
            if (counter != null) {
                final java.util.concurrent.locks.ReentrantLock l = lock;
                final java.util.concurrent.locks.Condition c = conditions.get(id);
                counter.setOnReset(() -> {
                    l.lock();
                    try { c.signalAll(); } finally { l.unlock(); }
                });
            }
        }

        return new TaskQueueManager(hierarchy, tpsGate, strategies, locks, conditions, threadPool);
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
        List<ExecutorSpec> executors = new ArrayList<>(List.of(
                ExecutorSpec.root("main", 1000, 5000),
                ExecutorSpec.child("fast", "main", 500, 2000),
                ExecutorSpec.child("bulk", "main", 300, 1000)
        ));

        // Create priority tree matching pool.yaml
        List<PriorityNodeConfig> priorityTree = new ArrayList<>(List.of(
                // L1.NORTH_AMERICA
                node("L1.NORTH_AMERICA", "$req.region == \"NORTH_AMERICA\"", null, null,
                        new ArrayList<>(List.of(
                                // L2.PLATINUM
                                node("L2.PLATINUM", "$req.customerTier == \"PLATINUM\"", null, null,
                                        new ArrayList<>(List.of(
                                                leaf("L3.HIGH_VALUE", "$req.transactionAmount > 100000",
                                                        sortBy("$req.priority", SortDirection.DESC), "fast"),
                                                leaf("L3.DEFAULT", "true",
                                                        sortBy("$req.urgency", SortDirection.DESC), "fast")
                                        ))),
                                // L2.GOLD
                                node("L2.GOLD", "$req.customerTier == \"GOLD\"", null, null,
                                        new ArrayList<>(List.of(
                                                leaf("L3.DEFAULT", "true", SortByConfig.fifo(), "fast")
                                        ))),
                                // L2.DEFAULT
                                node("L2.DEFAULT", "true", null, null,
                                        new ArrayList<>(List.of(
                                                leaf("L3.DEFAULT", "true", SortByConfig.fifo(), "bulk")
                                        )))
                        ))),
                // L1.EUROPE
                node("L1.EUROPE", "$req.region == \"EUROPE\"", null, null,
                        new ArrayList<>(List.of(
                                node("L2.DEFAULT", "true", null, null,
                                        new ArrayList<>(List.of(
                                                leaf("L3.DEFAULT", "true",
                                                        sortBy("$req.priority", SortDirection.DESC), "fast")
                                        )))
                        ))),
                // L1.DEFAULT
                node("L1.DEFAULT", "true", null, null,
                        new ArrayList<>(List.of(
                                node("L2.DEFAULT", "true", null, null,
                                        new ArrayList<>(List.of(
                                                leaf("L3.DEFAULT", "true", SortByConfig.fifo(), "bulk")
                                        )))
                        )))
        ));

        PoolConfig config = new PoolConfig();
        config.setName("test-pool");
        config.setVersion("1.0");
        AdaptersConfig adapters = new AdaptersConfig();
        adapters.setExecutors(executors);
        config.setAdapters(adapters);
        config.setPriorityTree(priorityTree);
        config.setPriorityStrategy(StrategyConfig.fifo());
        return config;
    }

    private static PriorityNodeConfig leaf(String name, String condition, SortByConfig sortBy, String executor) {
        PriorityNodeConfig node = new PriorityNodeConfig();
        node.setName(name);
        node.setCondition(condition);
        node.setSortBy(sortBy);
        node.setExecutor(executor);
        return node;
    }

    private static PriorityNodeConfig node(String name, String condition, SortByConfig sortBy, String executor,
                                           List<PriorityNodeConfig> nested) {
        PriorityNodeConfig n = new PriorityNodeConfig();
        n.setName(name);
        n.setCondition(condition);
        n.setSortBy(sortBy);
        n.setExecutor(executor);
        n.setNestedLevels(nested);
        return n;
    }

    private static SortByConfig sortBy(String field, SortDirection direction) {
        SortByConfig sb = new SortByConfig();
        sb.setField(field);
        sb.setDirection(direction);
        return sb;
    }
}
