package com.pool.adapter.executor.tps;

import com.pool.config.*;
import com.pool.core.TpsCounter;
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;
import com.pool.exception.TaskRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TpsPoolExecutor.
 */
class TpsPoolExecutorTest {

    private TpsPoolExecutor executor;
    private PoolConfig config;

    @BeforeEach
    void setUp() {
        config = createTestConfig();
        ExecutorHierarchy hierarchy = new ExecutorHierarchy(config.getExecutors());
        TpsGate tpsGate = new TpsGate(hierarchy);
        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("test-pool-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        TaskQueueManager queueManager = buildQueueManager(hierarchy, tpsGate, threadPool);
        com.pool.policy.PolicyEngine policyEngine = com.pool.policy.PolicyEngineFactory.create(config);
        executor = new TpsPoolExecutor(config, policyEngine, hierarchy, tpsGate, queueManager);
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private PoolConfig createTestConfig() {
        List<ExecutorSpec> executors = new ArrayList<>(List.of(
                ExecutorSpec.root("main", 100, 50),
                ExecutorSpec.child("vip", "main", 50),
                ExecutorSpec.child("bulk", "main", 30)
        ));

        PriorityNodeConfig vipNode = new PriorityNodeConfig();
        vipNode.setName("VIP");
        vipNode.setCondition("$req.tier == \"VIP\"");
        vipNode.setSortBy(SortByConfig.fifo());
        vipNode.setExecutor("vip");

        PriorityNodeConfig defaultNode = new PriorityNodeConfig();
        defaultNode.setName("DEFAULT");
        defaultNode.setCondition("true");
        defaultNode.setSortBy(SortByConfig.fifo());
        defaultNode.setExecutor("main");

        PoolConfig config = new PoolConfig();
        config.setName("test-pool");
        config.setVersion("1.0");
        AdaptersConfig adapters = new AdaptersConfig();
        adapters.setExecutors(executors);
        config.setAdapters(adapters);
        config.setPriorityTree(new ArrayList<>(List.of(vipNode, defaultNode)));
        config.setPriorityStrategy(StrategyConfig.fifo());
        return config;
    }

    @Test
    @DisplayName("Should submit and execute task")
    void shouldSubmitAndExecuteTask() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        TaskContext ctx = createTaskContext("main");

        Future<Integer> future = executor.submit(ctx, () -> {
            counter.incrementAndGet();
            return 42;
        });

        assertEquals(42, future.get(5, TimeUnit.SECONDS));
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("Should submit runnable task")
    void shouldSubmitRunnableTask() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        TaskContext ctx = createTaskContext("main");

        executor.submit(ctx, counter::incrementAndGet);

        // Wait for execution
        Thread.sleep(100);
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("Should route to correct executor based on policy")
    void shouldRouteToCorrectExecutor() throws Exception {
        AtomicInteger vipCounter = new AtomicInteger(0);
        AtomicInteger mainCounter = new AtomicInteger(0);

        // VIP request
        TaskContext vipCtx = TaskContextFactory.create(
                "{\"tier\": \"VIP\"}", Map.of()
        );
        executor.submit(vipCtx, vipCounter::incrementAndGet);

        // Regular request
        TaskContext mainCtx = TaskContextFactory.create(
                "{\"tier\": \"REGULAR\"}", Map.of()
        );
        executor.submit(mainCtx, mainCounter::incrementAndGet);

        Thread.sleep(100);

        assertEquals(1, vipCounter.get());
        assertEquals(1, mainCounter.get());
    }

    @Test
    @DisplayName("Should reject when shutdown")
    void shouldRejectWhenShutdown() {
        executor.shutdown();

        TaskContext ctx = createTaskContext("main");
        assertThrows(TaskRejectedException.class,
                () -> executor.submit(ctx, () -> {}));
    }

    @Test
    @DisplayName("Should throw on null context")
    void shouldThrowOnNullContext() {
        assertThrows(NullPointerException.class,
                () -> executor.submit(null, () -> {}));
    }

    @Test
    @DisplayName("Should throw on null task")
    void shouldThrowOnNullTask() {
        TaskContext ctx = createTaskContext("main");
        assertThrows(NullPointerException.class,
                () -> executor.submit(ctx, (Runnable) null));
    }

    @Test
    @DisplayName("Should report stats")
    void shouldReportStats() throws Exception {
        TaskContext ctx = createTaskContext("main");

        executor.submit(ctx, () -> {});
        Thread.sleep(100);

        TpsPoolExecutor.TpsExecutorStats stats = executor.getStats();
        assertEquals(1, stats.submitted());
        assertEquals(1, stats.executed());
        assertEquals(0, stats.rejected());
    }

    @Test
    @DisplayName("Should get queue size")
    void shouldGetQueueSize() {
        assertEquals(0, executor.getQueueSize());
    }

    @Test
    @DisplayName("Should get active count")
    void shouldGetActiveCount() {
        assertTrue(executor.getActiveCount() >= 0);
    }

    @Test
    @DisplayName("Should get current TPS for executor")
    void shouldGetCurrentTps() {
        assertEquals(0, executor.getCurrentTps("main"));
        assertEquals(0, executor.getCurrentTps("vip"));
    }

    @Test
    @DisplayName("Should get available TPS")
    void shouldGetAvailableTps() {
        assertEquals(100, executor.getAvailableTps("main"));
        assertEquals(50, executor.getAvailableTps("vip"));
    }

    @Test
    @DisplayName("Should get hierarchy and TPS gate")
    void shouldGetHierarchyAndGate() {
        assertNotNull(executor.getHierarchy());
        assertNotNull(executor.getTpsGate());
        assertEquals("main", executor.getHierarchy().getRootIds().iterator().next());
    }

    @Test
    @DisplayName("Should shutdown gracefully")
    void shouldShutdownGracefully() throws Exception {
        executor.shutdown();
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(executor.isTerminated());
    }

    @Test
    @DisplayName("Should shutdown immediately")
    void shouldShutdownImmediately() {
        executor.shutdownNow();
        assertTrue(executor.isShutdown());
    }

    @Test
    @DisplayName("Should handle multiple concurrent submissions")
    void shouldHandleConcurrentSubmissions() throws Exception {
        int taskCount = 50;
        AtomicInteger counter = new AtomicInteger(0);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            TaskContext ctx = createTaskContext("main");
            futures.add(executor.submit(ctx, () -> {
                counter.incrementAndGet();
                return 1;
            }));
        }

        // Wait for all to complete
        for (Future<Integer> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        assertEquals(taskCount, counter.get());
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

    private TaskContext createTaskContext(String executorHint) {
        return TaskContextFactory.create(
                "{\"tier\": \"REGULAR\", \"executorHint\": \"" + executorHint + "\"}",
                Map.of()
        );
    }
}
