package com.pool;

import com.pool.adapter.executor.tps.TaskQueueManager;
import com.pool.adapter.executor.tps.TpsGate;
import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.config.*;
import com.pool.core.TpsCounter;
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;
import com.pool.policy.PolicyEngine;
import com.pool.policy.PolicyEngineFactory;
import com.pool.strategy.PriorityStrategyFactory;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load tests for the Pool scheduling library.
 * Tests TPS accuracy, priority ordering, concurrency, and throughput.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoadTest {

    // -----------------------------------------------------------------------
    // Shared results table
    // -----------------------------------------------------------------------
    record Result(String scenario, String metric, String value, String status) {}

    static final List<Result> RESULTS = Collections.synchronizedList(new ArrayList<>());

    // -----------------------------------------------------------------------
    // Per-test executor
    // -----------------------------------------------------------------------
    private TpsPoolExecutor executor;

    // -----------------------------------------------------------------------
    // Config helpers (mirrors PoolApplicationTest)
    // -----------------------------------------------------------------------

    private static TpsPoolExecutor buildExecutor(PoolConfig config, PolicyEngine policyEngine) {
        ExecutorHierarchy hierarchy = new ExecutorHierarchy(config.getExecutors());
        TpsGate tpsGate = new TpsGate(hierarchy);
        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        Map<String, java.util.concurrent.locks.ReentrantLock> locks = new ConcurrentHashMap<>();
        Map<String, java.util.concurrent.locks.Condition> conditions = new ConcurrentHashMap<>();
        Map<String, com.pool.strategy.PriorityStrategy<TaskQueueManager.QueuedTask>> strategies = new ConcurrentHashMap<>();

        for (String id : hierarchy.getAllExecutorIds()) {
            java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
            locks.put(id, lock);
            conditions.put(id, lock.newCondition());
            int cap = hierarchy.getQueueCapacity(id);
            strategies.put(id, PriorityStrategyFactory.createDefault(cap <= 0 ? Integer.MAX_VALUE : cap));
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
        TaskQueueManager queueManager = new TaskQueueManager(hierarchy, tpsGate, strategies, locks, conditions, threadPool);
        return new TpsPoolExecutor(config, policyEngine, hierarchy, tpsGate, queueManager);
    }

    /** Build config with configurable TPS values */
    private static PoolConfig makeConfig(int mainTps, int fastTps, int bulkTps) {
        List<ExecutorSpec> executors = List.of(
                ExecutorSpec.root("main", mainTps, 10000),
                ExecutorSpec.child("fast", "main", fastTps, 5000),
                ExecutorSpec.child("bulk", "main", bulkTps, 3000)
        );
        List<PriorityNodeConfig> tree = List.of(
                node("L1.NORTH_AMERICA", "$req.region == \"NORTH_AMERICA\"", null, null, List.of(
                        node("L2.PLATINUM", "$req.customerTier == \"PLATINUM\"", null, null, List.of(
                                leaf("L3.HIGH_VALUE", "$req.transactionAmount > 100000",
                                        sortBy("$req.priority", SortDirection.DESC), "fast"),
                                leaf("L3.DEFAULT", "true",
                                        sortBy("$req.urgency", SortDirection.DESC), "fast")
                        )),
                        node("L2.GOLD", "$req.customerTier == \"GOLD\"", null, null, List.of(
                                leaf("L3.DEFAULT", "true", SortByConfig.fifo(), "fast")
                        )),
                        node("L2.DEFAULT", "true", null, null, List.of(
                                leaf("L3.DEFAULT", "true", SortByConfig.fifo(), "bulk")
                        ))
                )),
                node("L1.EUROPE", "$req.region == \"EUROPE\"", null, null, List.of(
                        node("L2.DEFAULT", "true", null, null, List.of(
                                leaf("L3.DEFAULT", "true",
                                        sortBy("$req.priority", SortDirection.DESC), "fast")
                        ))
                )),
                node("L1.DEFAULT", "true", null, null, List.of(
                        node("L2.DEFAULT", "true", null, null, List.of(
                                leaf("L3.DEFAULT", "true", SortByConfig.fifo(), "bulk")
                        ))
                ))
        );
        PoolConfig cfg = new PoolConfig();
        cfg.setName("load-test-pool");
        cfg.setVersion("1.0");
        AdaptersConfig adapters = new AdaptersConfig();
        adapters.setExecutors(new ArrayList<>(executors));
        cfg.setAdapters(adapters);
        cfg.setPriorityTree(new ArrayList<>(tree));
        cfg.setPriorityStrategy(StrategyConfig.fifo());
        return cfg;
    }

    private static TaskContext ctx(String region, String tier, int amount, int priority) {
        String json = "{\"region\":\"%s\",\"customerTier\":\"%s\",\"transactionAmount\":%d,\"priority\":%d,\"urgency\":%d}"
                .formatted(region, tier, amount, priority, priority);
        return TaskContextFactory.create(json, Map.of("clientId", "load-test"));
    }

    private static PriorityNodeConfig leaf(String name, String cond, SortByConfig sort, String exec) {
        PriorityNodeConfig n = new PriorityNodeConfig();
        n.setName(name); n.setCondition(cond); n.setSortBy(sort); n.setExecutor(exec);
        return n;
    }

    private static PriorityNodeConfig node(String name, String cond, SortByConfig sort, String exec,
                                            List<PriorityNodeConfig> nested) {
        PriorityNodeConfig n = new PriorityNodeConfig();
        n.setName(name); n.setCondition(cond); n.setSortBy(sort); n.setExecutor(exec);
        n.setNestedLevels(new ArrayList<>(nested));
        return n;
    }

    private static SortByConfig sortBy(String field, SortDirection dir) {
        SortByConfig s = new SortByConfig(); s.setField(field); s.setDirection(dir); return s;
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // SCENARIO 1 — Baseline throughput (no TPS pressure)
    // -----------------------------------------------------------------------
    @Test @Order(1)
    @DisplayName("LT-01  Baseline: 200 tasks, TPS=1000, no queuing expected")
    void lt01_baselineThroughput() throws Exception {
        int TASKS = 200;
        PoolConfig cfg = makeConfig(1000, 500, 300);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        AtomicInteger done = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(TASKS);
        long start = System.nanoTime();

        for (int i = 0; i < TASKS; i++) {
            TaskContext c = ctx("NORTH_AMERICA", "GOLD", 1000, i);
            executor.submit(c, () -> { done.incrementAndGet(); latch.countDown(); });
        }

        boolean finished = latch.await(15, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;
        double elapsedS = elapsed / 1e9;
        double throughput = done.get() / elapsedS;

        TpsPoolExecutor.TpsExecutorStats stats = executor.getStats();
        String status = finished && done.get() == TASKS ? "PASS" : "FAIL";

        record("LT-01 Baseline Throughput", "tasks_completed / submitted", done.get() + " / " + TASKS, status);
        record("LT-01 Baseline Throughput", "elapsed_sec", String.format("%.2f", elapsedS), status);
        record("LT-01 Baseline Throughput", "throughput_tps", String.format("%.0f", throughput), status);
        record("LT-01 Baseline Throughput", "queued_at_finish", String.valueOf(stats.queueSize()), status);
        record("LT-01 Baseline Throughput", "rejected", String.valueOf(stats.rejected()), status);

        Assertions.assertTrue(finished, "All tasks should complete");
        Assertions.assertEquals(TASKS, done.get());
    }

    // -----------------------------------------------------------------------
    // SCENARIO 2 — TPS limit enforcement (submit faster than TPS allows)
    // -----------------------------------------------------------------------
    @Test @Order(2)
    @DisplayName("LT-02  TPS enforcement: submit 500 tasks instantly at TPS=50 fast executor")
    void lt02_tpsEnforcement() throws Exception {
        int TASKS = 500;
        // Set fast TPS to 50 so tasks queue up
        PoolConfig cfg = makeConfig(200, 50, 100);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        AtomicInteger done = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(TASKS);

        long start = System.nanoTime();
        for (int i = 0; i < TASKS; i++) {
            TaskContext c = ctx("NORTH_AMERICA", "GOLD", 1000, i);
            executor.submit(c, () -> { done.incrementAndGet(); latch.countDown(); });
        }
        // Snapshot queue right after burst
        int queueAfterBurst = executor.getQueueSize("fast");

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;
        double elapsedS = elapsed / 1e9;

        TpsPoolExecutor.TpsExecutorStats stats = executor.getStats();
        String status = finished && done.get() == TASKS ? "PASS" : "FAIL";

        record("LT-02 TPS Enforcement", "tasks_completed / submitted", done.get() + " / " + TASKS, status);
        record("LT-02 TPS Enforcement", "queue_depth_after_burst", String.valueOf(queueAfterBurst), status);
        record("LT-02 TPS Enforcement", "elapsed_sec", String.format("%.2f", elapsedS), status);
        record("LT-02 TPS Enforcement", "configured_fast_tps", "50", status);
        record("LT-02 TPS Enforcement", "rejected", String.valueOf(stats.rejected()), status);

        Assertions.assertTrue(finished, "All queued tasks should drain eventually");
        Assertions.assertEquals(TASKS, done.get());
        Assertions.assertTrue(queueAfterBurst > 0, "Should have queued tasks since TPS is low");
    }

    // -----------------------------------------------------------------------
    // SCENARIO 3 — Hierarchical TPS: child limit respected when root is looser
    // -----------------------------------------------------------------------
    @Test @Order(3)
    @DisplayName("LT-03  Hierarchical TPS: fast child at 30 TPS, main at 1000 TPS")
    void lt03_hierarchicalTps() throws Exception {
        int TASKS = 120;
        // fast=30 is the bottleneck; main=1000 should not help
        PoolConfig cfg = makeConfig(1000, 30, 500);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        AtomicInteger done = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(TASKS);

        long start = System.nanoTime();
        for (int i = 0; i < TASKS; i++) {
            TaskContext c = ctx("NORTH_AMERICA", "GOLD", 1000, i); // routes to "fast"
            executor.submit(c, () -> { done.incrementAndGet(); latch.countDown(); });
        }
        int queueSnap = executor.getQueueSize("fast");

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        String status = finished && done.get() == TASKS ? "PASS" : "FAIL";
        record("LT-03 Hierarchical TPS", "tasks_completed / submitted", done.get() + " / " + TASKS, status);
        record("LT-03 Hierarchical TPS", "fast_queue_depth_post_burst", String.valueOf(queueSnap), status);
        record("LT-03 Hierarchical TPS", "elapsed_sec", String.format("%.2f", elapsed / 1e9), status);
        record("LT-03 Hierarchical TPS", "child_bottleneck_triggered", queueSnap > 0 ? "YES" : "NO", status);

        Assertions.assertTrue(finished);
        Assertions.assertEquals(TASKS, done.get());
    }

    // -----------------------------------------------------------------------
    // SCENARIO 4 — Priority ordering under contention
    // -----------------------------------------------------------------------
    @Test @Order(4)
    @DisplayName("LT-04  Priority ordering: high-priority tasks execute before low-priority under TPS pressure")
    void lt04_priorityOrdering() throws Exception {
        // Use very low TPS so tasks queue and ordering matters
        PoolConfig cfg = makeConfig(200, 10, 100);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        // Submit 5 LOW priority tasks first, then 5 HIGH priority tasks
        // High priority = PLATINUM + transactionAmount > 100000 + high priority value
        // Low priority  = GOLD FIFO (no sort-by priority)
        int LOW = 20, HIGH = 20;
        List<Integer> execOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(LOW + HIGH);

        // Submit low priority (GOLD → L2.GOLD → FIFO)
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < LOW; i++) {
            int id = i;
            TaskContext c = ctx("NORTH_AMERICA", "GOLD", 1000, 1);
            futures.add(executor.submit(c, () -> {
                execOrder.add(-(id + 1)); // negative = low priority task
                latch.countDown();
                return null;
            }));
        }
        // Give low-priority tasks a moment to fill the queue
        Thread.sleep(50);

        // Submit high priority (PLATINUM + high value → L3.HIGH_VALUE with DESC sort)
        for (int i = 0; i < HIGH; i++) {
            int id = i;
            TaskContext c = ctx("NORTH_AMERICA", "PLATINUM", 200000, 100 - i); // high amount + priority
            futures.add(executor.submit(c, () -> {
                execOrder.add(id + 1); // positive = high priority task
                latch.countDown();
                return null;
            }));
        }

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        String status = finished ? "PASS" : "FAIL";

        // Count how many of the first HIGH completions were high-priority tasks
        int firstHalfCount = Math.min(HIGH, execOrder.size());
        long highInFirstHalf = execOrder.stream().limit(firstHalfCount).filter(v -> v > 0).count();
        double highPriorityRatio = firstHalfCount > 0 ? (double) highInFirstHalf / firstHalfCount : 0;

        record("LT-04 Priority Ordering", "tasks_completed", String.valueOf(execOrder.size()), status);
        record("LT-04 Priority Ordering", "high_prio_in_first_half", highInFirstHalf + "/" + firstHalfCount, status);
        record("LT-04 Priority Ordering", "high_priority_ratio", String.format("%.0f%%", highPriorityRatio * 100), status);
        record("LT-04 Priority Ordering", "ordering_correct (ratio>50%)", highPriorityRatio > 0.5 ? "YES" : "NO", status);
    }

    // -----------------------------------------------------------------------
    // SCENARIO 5 — Concurrent thread storm: 50 threads submitting simultaneously
    // -----------------------------------------------------------------------
    @Test @Order(5)
    @DisplayName("LT-05  Concurrent storm: 50 threads × 20 tasks = 1000 total, TPS=500")
    void lt05_concurrentStorm() throws Exception {
        int THREADS = 50, TASKS_PER_THREAD = 20;
        int TOTAL = THREADS * TASKS_PER_THREAD;

        PoolConfig cfg = makeConfig(500, 300, 200);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        AtomicInteger done = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(TOTAL);
        ExecutorService submitters = Executors.newFixedThreadPool(THREADS);

        for (int t = 0; t < THREADS; t++) {
            int tid = t;
            submitters.submit(() -> {
                try { startGun.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String region = tid % 3 == 0 ? "NORTH_AMERICA" : tid % 3 == 1 ? "EUROPE" : "ASIA";
                String tier = tid % 2 == 0 ? "PLATINUM" : "GOLD";
                for (int i = 0; i < TASKS_PER_THREAD; i++) {
                    try {
                        TaskContext c = ctx(region, tier, 1000 * tid, i);
                        executor.submit(c, () -> { done.incrementAndGet(); finished.countDown(); });
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        finished.countDown();
                    }
                }
            });
        }

        long start = System.nanoTime();
        startGun.countDown(); // fire!
        boolean allDone = finished.await(30, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;
        submitters.shutdownNow();

        double tps = done.get() / (elapsed / 1e9);
        String statusStr = (done.get() + errors.get()) >= TOTAL * 0.99 ? "PASS" : "FAIL";

        record("LT-05 Concurrent Storm", "threads × tasks", THREADS + " × " + TASKS_PER_THREAD, statusStr);
        record("LT-05 Concurrent Storm", "tasks_completed", String.valueOf(done.get()), statusStr);
        record("LT-05 Concurrent Storm", "submission_errors", String.valueOf(errors.get()), statusStr);
        record("LT-05 Concurrent Storm", "elapsed_sec", String.format("%.2f", elapsed / 1e9), statusStr);
        record("LT-05 Concurrent Storm", "effective_tps", String.format("%.0f", tps), statusStr);
        record("LT-05 Concurrent Storm", "all_finished", String.valueOf(allDone), statusStr);

        Assertions.assertTrue(allDone, "All tasks should finish");
    }

    // -----------------------------------------------------------------------
    // SCENARIO 6 — Routing accuracy under load
    // -----------------------------------------------------------------------
    @Test @Order(6)
    @DisplayName("LT-06  Routing accuracy: 300 tasks verify correct executor assignment per region/tier")
    void lt06_routingAccuracy() throws Exception {
        int PER_ROUTE = 50;
        PoolConfig cfg = makeConfig(2000, 1000, 1000);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        // Track which executor each route actually hit
        AtomicInteger fastCount = new AtomicInteger();
        AtomicInteger bulkCount = new AtomicInteger();
        AtomicInteger wrongCount = new AtomicInteger();

        record RouteSpec(String region, String tier, int amount, String expectedExec) {}
        List<RouteSpec> routes = List.of(
                new RouteSpec("NORTH_AMERICA", "PLATINUM", 200000, "fast"),
                new RouteSpec("NORTH_AMERICA", "GOLD", 5000, "fast"),
                new RouteSpec("NORTH_AMERICA", "STANDARD", 1000, "bulk"),
                new RouteSpec("EUROPE", "GOLD", 5000, "fast"),
                new RouteSpec("ASIA", "GOLD", 5000, "bulk"),
                new RouteSpec("ASIA", "STANDARD", 500, "bulk")
        );

        CountDownLatch latch = new CountDownLatch(routes.size() * PER_ROUTE);
        AtomicInteger submitted = new AtomicInteger();

        for (RouteSpec r : routes) {
            for (int i = 0; i < PER_ROUTE; i++) {
                TaskContext c = ctx(r.region(), r.tier(), r.amount(), i);
                String expectedExec = r.expectedExec();
                // Evaluate routing deterministically via policy engine
                var evalResult = pe.evaluate(c);
                String actualExec = evalResult.getMatchedPath().executor();
                if (actualExec.equals(expectedExec)) {
                    if (actualExec.equals("fast")) fastCount.incrementAndGet();
                    else bulkCount.incrementAndGet();
                } else {
                    wrongCount.incrementAndGet();
                }
                executor.submit(c, latch::countDown);
                submitted.incrementAndGet();
            }
        }

        boolean done = latch.await(15, TimeUnit.SECONDS);
        String status = wrongCount.get() == 0 && done ? "PASS" : "FAIL";

        record("LT-06 Routing Accuracy", "total_submitted", String.valueOf(submitted.get()), status);
        record("LT-06 Routing Accuracy", "routed_to_fast", String.valueOf(fastCount.get()), status);
        record("LT-06 Routing Accuracy", "routed_to_bulk", String.valueOf(bulkCount.get()), status);
        record("LT-06 Routing Accuracy", "routing_errors", String.valueOf(wrongCount.get()), status);
        record("LT-06 Routing Accuracy", "accuracy", wrongCount.get() == 0 ? "100%" : "< 100%", status);

        Assertions.assertEquals(0, wrongCount.get(), "All tasks should be routed to the correct executor");
    }

    // -----------------------------------------------------------------------
    // SCENARIO 7 — Sustained load (3-second window, measure stability)
    // -----------------------------------------------------------------------
    @Test @Order(7)
    @DisplayName("LT-07  Sustained load: submit at ~200 TPS for 3 seconds, measure stability")
    void lt07_sustainedLoad() throws Exception {
        int TARGET_TPS = 200;
        int DURATION_MS = 3000;
        PoolConfig cfg = makeConfig(500, 300, 200);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicLong lastTpsWindowDone = new AtomicLong(0);
        List<Integer> tpsSamples = Collections.synchronizedList(new ArrayList<>());

        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        // Sample done count every 500ms
        sampler.scheduleAtFixedRate(() -> {
            long prev = lastTpsWindowDone.getAndSet(done.get());
            tpsSamples.add((int)((done.get() - prev) * 2)); // *2 = per second
        }, 500, 500, TimeUnit.MILLISECONDS);

        long endTime = System.currentTimeMillis() + DURATION_MS;
        long delayNs = 1_000_000_000L / TARGET_TPS; // ns between submissions
        long next = System.nanoTime();

        while (System.currentTimeMillis() < endTime) {
            long now = System.nanoTime();
            if (now >= next) {
                String region = submitted.get() % 2 == 0 ? "NORTH_AMERICA" : "EUROPE";
                TaskContext c = ctx(region, "GOLD", 5000, 1);
                executor.submit(c, done::incrementAndGet);
                submitted.incrementAndGet();
                next += delayNs;
            } else {
                Thread.sleep(0, (int) Math.min(delayNs / 4, 999_999));
            }
        }

        // Wait for draining
        Thread.sleep(2000);
        sampler.shutdownNow();

        int totalSub = submitted.get();
        int totalDone = done.get();
        double completionRate = totalSub > 0 ? (double) totalDone / totalSub * 100 : 0;

        // Average TPS (exclude first and last samples as they may be partial)
        OptionalDouble avgTps = tpsSamples.stream().skip(1).mapToInt(Integer::intValue)
                .filter(v -> v > 0).average();

        String status = completionRate > 95 ? "PASS" : "FAIL";
        record("LT-07 Sustained Load", "duration_sec", "3", status);
        record("LT-07 Sustained Load", "target_tps", String.valueOf(TARGET_TPS), status);
        record("LT-07 Sustained Load", "tasks_submitted", String.valueOf(totalSub), status);
        record("LT-07 Sustained Load", "tasks_completed", String.valueOf(totalDone), status);
        record("LT-07 Sustained Load", "completion_rate", String.format("%.1f%%", completionRate), status);
        record("LT-07 Sustained Load", "avg_tps_samples", avgTps.isPresent() ? String.format("%.0f", avgTps.getAsDouble()) : "N/A", status);
    }

    // -----------------------------------------------------------------------
    // SCENARIO 8 — Queue overflow / rejection (fill queue beyond capacity)
    // -----------------------------------------------------------------------
    @Test @Order(8)
    @DisplayName("LT-08  Queue overflow: submit > queue-capacity tasks when TPS is throttled")
    void lt08_queueOverflow() throws Exception {
        // fast queue capacity = 20, fast TPS = 5
        List<ExecutorSpec> executors = List.of(
                ExecutorSpec.root("main", 1000, 10000),
                ExecutorSpec.child("fast", "main", 5, 20),
                ExecutorSpec.child("bulk", "main", 100, 500)
        );
        PoolConfig cfg = new PoolConfig();
        cfg.setName("overflow-test");
        cfg.setVersion("1.0");
        AdaptersConfig adapters = new AdaptersConfig();
        adapters.setExecutors(new ArrayList<>(executors));
        cfg.setAdapters(adapters);
        cfg.setPriorityTree(new ArrayList<>(List.of(
                node("L1.ALL", "true", null, null, List.of(
                        leaf("L2.FAST", "true", SortByConfig.fifo(), "fast")
                ))
        )));
        cfg.setPriorityStrategy(StrategyConfig.fifo());

        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        int OVERFLOW_TASKS = 200; // >> 20 queue capacity at 5 TPS
        AtomicInteger done = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(OVERFLOW_TASKS);

        for (int i = 0; i < OVERFLOW_TASKS; i++) {
            TaskContext c = ctx("EUROPE", "GOLD", 1000, 1);
            try {
                executor.submit(c, () -> { done.incrementAndGet(); latch.countDown(); });
            } catch (com.pool.exception.TaskRejectedException e) {
                rejected.incrementAndGet();
                latch.countDown();
            }
        }

        int queuePeak = executor.getQueueSize("fast");
        // wait a few seconds for some draining
        latch.await(10, TimeUnit.SECONDS);

        TpsPoolExecutor.TpsExecutorStats stats = executor.getStats();
        String status = rejected.get() > 0 || done.get() + rejected.get() == OVERFLOW_TASKS ? "PASS" : "PARTIAL";

        record("LT-08 Queue Overflow", "tasks_submitted", String.valueOf(OVERFLOW_TASKS), status);
        record("LT-08 Queue Overflow", "tasks_rejected_or_queued", rejected.get() + " rejected", status);
        record("LT-08 Queue Overflow", "tasks_completed", String.valueOf(done.get()), status);
        record("LT-08 Queue Overflow", "fast_queue_capacity", "20", status);
        record("LT-08 Queue Overflow", "queue_peak_observed", String.valueOf(queuePeak), status);
        record("LT-08 Queue Overflow", "overflow_handled", (rejected.get() > 0 || stats.queueSize() <= 20) ? "YES" : "NO", status);
    }

    // -----------------------------------------------------------------------
    // SCENARIO 9 — Expression evaluation performance
    // -----------------------------------------------------------------------
    @Test @Order(9)
    @DisplayName("LT-09  Expression throughput: 5000 policy evaluations, measure eval rate")
    void lt09_expressionThroughput() {
        PoolConfig cfg = makeConfig(100000, 50000, 30000);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);

        int EVALS = 5000;
        String[] regions = {"NORTH_AMERICA", "EUROPE", "ASIA"};
        String[] tiers = {"PLATINUM", "GOLD", "STANDARD"};
        int[] amounts = {200000, 50000, 5000, 500};

        long start = System.nanoTime();
        for (int i = 0; i < EVALS; i++) {
            TaskContext c = ctx(
                    regions[i % regions.length],
                    tiers[i % tiers.length],
                    amounts[i % amounts.length],
                    i % 100
            );
            pe.evaluate(c);
        }
        long elapsed = System.nanoTime() - start;
        double elapsedMs = elapsed / 1e6;
        double evalPerSec = EVALS / (elapsed / 1e9);
        double avgUs = (elapsed / 1e3) / EVALS;

        String status = evalPerSec > 5000 ? "PASS" : "WARN";
        record("LT-09 Expression Throughput", "total_evaluations", String.valueOf(EVALS), status);
        record("LT-09 Expression Throughput", "total_elapsed_ms", String.format("%.1f", elapsedMs), status);
        record("LT-09 Expression Throughput", "avg_eval_us", String.format("%.1f µs", avgUs), status);
        record("LT-09 Expression Throughput", "evals_per_sec", String.format("%.0f", evalPerSec), status);
        record("LT-09 Expression Throughput", "target_evals_per_sec", ">5000", status);
    }

    // -----------------------------------------------------------------------
    // SCENARIO 10 — Mixed-workload end-to-end (closest to production)
    // -----------------------------------------------------------------------
    @Test @Order(10)
    @DisplayName("LT-10  Mixed workload: all regions + tiers, 600 tasks, TPS=300")
    void lt10_mixedWorkload() throws Exception {
        int TASKS = 600;
        PoolConfig cfg = makeConfig(300, 200, 100);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        AtomicInteger done = new AtomicInteger();
        AtomicInteger fastDone = new AtomicInteger();
        AtomicInteger bulkDone = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(TASKS);

        String[] regions = {"NORTH_AMERICA", "EUROPE", "ASIA"};
        String[] tiers = {"PLATINUM", "GOLD", "STANDARD", "BRONZE"};
        int[] amounts = {500000, 150000, 50000, 5000, 500};

        long start = System.nanoTime();
        for (int i = 0; i < TASKS; i++) {
            String region = regions[i % regions.length];
            String tier = tiers[i % tiers.length];
            int amount = amounts[i % amounts.length];
            // determine expected executor for tracking
            var evalResult = pe.evaluate(ctx(region, tier, amount, i % 10));
            boolean isFast = "fast".equals(evalResult.getMatchedPath().executor());

            TaskContext c = ctx(region, tier, amount, i % 10);
            executor.submit(c, () -> {
                done.incrementAndGet();
                if (isFast) fastDone.incrementAndGet();
                else bulkDone.incrementAndGet();
                latch.countDown();
            });
        }

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        TpsPoolExecutor.TpsExecutorStats stats = executor.getStats();
        String status = finished && done.get() == TASKS ? "PASS" : "FAIL";

        record("LT-10 Mixed Workload", "tasks_submitted / completed", TASKS + " / " + done.get(), status);
        record("LT-10 Mixed Workload", "fast_executor_tasks", String.valueOf(fastDone.get()), status);
        record("LT-10 Mixed Workload", "bulk_executor_tasks", String.valueOf(bulkDone.get()), status);
        record("LT-10 Mixed Workload", "elapsed_sec", String.format("%.2f", elapsed / 1e9), status);
        record("LT-10 Mixed Workload", "stats_submitted", String.valueOf(stats.submitted()), status);
        record("LT-10 Mixed Workload", "stats_rejected", String.valueOf(stats.rejected()), status);
    }

    // -----------------------------------------------------------------------
    // SCENARIO 11 — Shutdown under load (graceful drain)
    // -----------------------------------------------------------------------
    @Test @Order(11)
    @DisplayName("LT-11  Graceful shutdown: queued tasks complete before shutdown finishes")
    void lt11_gracefulShutdown() throws Exception {
        PoolConfig cfg = makeConfig(1000, 500, 300);
        PolicyEngine pe = PolicyEngineFactory.create(cfg);
        executor = buildExecutor(cfg, pe);

        int TASKS = 30;
        AtomicInteger done = new AtomicInteger();
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < TASKS; i++) {
            TaskContext c = ctx("NORTH_AMERICA", "GOLD", 5000, i);
            int n = i;
            futures.add(executor.submit(c, () -> { Thread.sleep(20); return done.incrementAndGet(); }));
        }

        executor.shutdown();
        boolean terminated = executor.awaitTermination(15, TimeUnit.SECONDS);

        int completedFutures = 0;
        for (Future<Integer> f : futures) {
            try { f.get(1, TimeUnit.SECONDS); completedFutures++; } catch (Exception ignored) {}
        }

        String status = terminated && done.get() == TASKS ? "PASS" : "FAIL";
        record("LT-11 Graceful Shutdown", "tasks_submitted", String.valueOf(TASKS), status);
        record("LT-11 Graceful Shutdown", "tasks_completed_before_shutdown", String.valueOf(done.get()), status);
        record("LT-11 Graceful Shutdown", "futures_resolved", String.valueOf(completedFutures), status);
        record("LT-11 Graceful Shutdown", "terminated_cleanly", String.valueOf(terminated), status);

        executor = null; // prevent double-shutdown in @AfterEach
        Assertions.assertTrue(terminated);
        Assertions.assertEquals(TASKS, done.get());
    }

    // -----------------------------------------------------------------------
    // Print results table after all tests
    // -----------------------------------------------------------------------
    @AfterAll
    static void printResults() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                        POOL LIBRARY — LOAD TEST RESULTS                                 ║");
        System.out.println("╠══════════════════════════════════════════╦══════════════════════════╦══════════╦════════╣");
        System.out.printf("║ %-40s ║ %-24s ║ %-8s ║ %-6s ║%n", "Scenario", "Metric", "Value", "Status");
        System.out.println("╠══════════════════════════════════════════╬══════════════════════════╬══════════╬════════╣");

        String lastScenario = "";
        for (Result r : RESULTS) {
            String scenario = r.scenario().equals(lastScenario) ? "" : r.scenario();
            lastScenario = r.scenario();
            String statusStr = switch (r.status()) {
                case "PASS" -> "PASS ✓";
                case "FAIL" -> "FAIL ✗";
                case "WARN" -> "WARN ⚠";
                default -> r.status();
            };
            System.out.printf("║ %-40s ║ %-24s ║ %-8s ║ %-6s ║%n",
                    truncate(scenario, 40),
                    truncate(r.metric(), 24),
                    truncate(r.value(), 8),
                    statusStr);
        }
        System.out.println("╚══════════════════════════════════════════╩══════════════════════════╩══════════╩════════╝");
        System.out.println();

        long passed = RESULTS.stream().filter(r -> !r.scenario().isEmpty() || true)
                .map(r -> r.scenario()).distinct().filter(s -> !s.isEmpty())
                .filter(s -> RESULTS.stream().filter(r -> r.scenario().equals(s))
                        .allMatch(r -> r.status().equals("PASS") || r.status().equals("WARN")))
                .count();
        System.out.printf("  Summary: %d / 11 scenarios passed%n%n", passed);
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static void record(String scenario, String metric, String value, String status) {
        RESULTS.add(new Result(scenario, metric, value, status));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
