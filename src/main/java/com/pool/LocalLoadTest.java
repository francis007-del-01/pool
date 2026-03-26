package com.pool;

import com.pool.adapter.executor.tps.TaskQueueManager;
import com.pool.adapter.executor.tps.TpsGate;
import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.config.*;
import com.pool.core.SlidingWindowCounter;
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;
import com.pool.exception.TaskRejectedException;
import com.pool.policy.PolicyEngine;
import com.pool.policy.PolicyEngineFactory;
import com.pool.strategy.PriorityStrategyFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone local load test for the Pool scheduling library.
 * Run with: mvn exec:java -Dexec.mainClass=com.pool.LocalLoadTest -q
 */
public class LocalLoadTest {

    // -----------------------------------------------------------------------
    // Result accumulation
    // -----------------------------------------------------------------------
    record Row(String scenario, String metric, String value, String status) {}
    static final List<Row> ROWS = new ArrayList<>();
    static int scenariosRun = 0, scenariosPassed = 0;

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        banner("POOL LIBRARY — LOCAL LOAD TEST");
        System.out.println("  Wiring up executors directly (no Spring, no JUnit)\n");

        run("LT-01  Baseline throughput — 200 tasks, TPS=1000",          LocalLoadTest::lt01);
        run("LT-02  TPS enforcement — 500 tasks burst at fast TPS=50",   LocalLoadTest::lt02);
        run("LT-03  Hierarchical TPS — child bottleneck at 30 TPS",      LocalLoadTest::lt03);
        run("LT-04  Priority ordering — high vs low under pressure",      LocalLoadTest::lt04);
        run("LT-05  Concurrent storm — 50 threads × 20 tasks",           LocalLoadTest::lt05);
        run("LT-06  Routing accuracy — 300 tasks across all routes",      LocalLoadTest::lt06);
        run("LT-07  Sustained load — 200 TPS for 3 seconds",             LocalLoadTest::lt07);
        run("LT-08  Queue overflow — 200 tasks into capacity-20 queue",   LocalLoadTest::lt08);
        run("LT-09  Expression throughput — 5000 policy evaluations",     LocalLoadTest::lt09);
        run("LT-10  Mixed workload — 600 tasks, all regions/tiers",       LocalLoadTest::lt10);
        run("LT-11  Graceful shutdown — drain before terminate",          LocalLoadTest::lt11);

        printTable();
    }

    // -----------------------------------------------------------------------
    // SCENARIO 1 — Baseline throughput
    // -----------------------------------------------------------------------
    static void lt01(String scenario) throws Exception {
        int TASKS = 200;
        var exec = buildExecutor(makeConfig(1000, 500, 300));
        try {
            var done = new AtomicInteger();
            var latch = new CountDownLatch(TASKS);
            long start = System.nanoTime();

            for (int i = 0; i < TASKS; i++) {
                exec.submit(ctx("NORTH_AMERICA", "GOLD", 1000, i), () -> { done.incrementAndGet(); latch.countDown(); });
            }
            boolean ok = latch.await(15, TimeUnit.SECONDS);
            double elapsedS = (System.nanoTime() - start) / 1e9;

            TpsPoolExecutor.TpsExecutorStats stats = exec.getStats();
            String pass = ok && done.get() == TASKS ? "PASS" : "FAIL";
            row(scenario, "submitted / completed",  TASKS + " / " + done.get(), pass);
            row(scenario, "elapsed",                fmt2(elapsedS) + " s", pass);
            row(scenario, "throughput",             fmtK(done.get() / elapsedS) + " TPS", pass);
            row(scenario, "queue at finish",        String.valueOf(stats.queueSize()), pass);
            row(scenario, "rejected",               String.valueOf(stats.rejected()), pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 2 — TPS enforcement
    // -----------------------------------------------------------------------
    static void lt02(String scenario) throws Exception {
        int TASKS = 500;
        var exec = buildExecutor(makeConfig(200, 50, 100));
        try {
            var done = new AtomicInteger();
            var latch = new CountDownLatch(TASKS);

            long start = System.nanoTime();
            for (int i = 0; i < TASKS; i++) {
                exec.submit(ctx("NORTH_AMERICA", "GOLD", 1000, i), () -> { done.incrementAndGet(); latch.countDown(); });
            }
            int queueSnap = exec.getQueueSize("fast");
            boolean ok = latch.await(30, TimeUnit.SECONDS);
            double elapsedS = (System.nanoTime() - start) / 1e9;

            TpsPoolExecutor.TpsExecutorStats stats = exec.getStats();
            String pass = ok && done.get() == TASKS ? "PASS" : "FAIL";
            row(scenario, "submitted / completed",  TASKS + " / " + done.get(), pass);
            row(scenario, "queue depth after burst", String.valueOf(queueSnap), pass);
            row(scenario, "elapsed",                fmt2(elapsedS) + " s", pass);
            row(scenario, "configured fast TPS",    "50", pass);
            row(scenario, "tasks rejected",         String.valueOf(stats.rejected()), pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 3 — Hierarchical TPS
    // -----------------------------------------------------------------------
    static void lt03(String scenario) throws Exception {
        int TASKS = 120;
        var exec = buildExecutor(makeConfig(1000, 30, 500));
        try {
            var done = new AtomicInteger();
            var latch = new CountDownLatch(TASKS);

            long start = System.nanoTime();
            for (int i = 0; i < TASKS; i++) {
                exec.submit(ctx("NORTH_AMERICA", "GOLD", 1000, i), () -> { done.incrementAndGet(); latch.countDown(); });
            }
            int queueSnap = exec.getQueueSize("fast");
            boolean ok = latch.await(30, TimeUnit.SECONDS);
            double elapsedS = (System.nanoTime() - start) / 1e9;

            String pass = ok && done.get() == TASKS ? "PASS" : "FAIL";
            row(scenario, "submitted / completed",      TASKS + " / " + done.get(), pass);
            row(scenario, "fast queue depth (snapshot)", String.valueOf(queueSnap), pass);
            row(scenario, "elapsed",                    fmt2(elapsedS) + " s", pass);
            row(scenario, "child bottleneck triggered", queueSnap > 0 ? "YES" : "NO", pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 4 — Priority ordering under contention
    // -----------------------------------------------------------------------
    static void lt04(String scenario) throws Exception {
        int LOW = 20, HIGH = 20;
        // fast TPS = 10; we pre-saturate the window so ALL measured tasks queue up
        var exec = buildExecutor(makeConfig(200, 10, 100));
        try {
            var execOrder = Collections.synchronizedList(new ArrayList<Integer>());

            // Pre-saturate: fill the TPS window with dummy tasks so the window is full
            // before we submit the tasks we want to measure ordering on.
            var saturateLatch = new CountDownLatch(10);
            for (int i = 0; i < 10; i++) {
                exec.submit(ctx("NORTH_AMERICA", "GOLD", 1000, 1), () -> {
                    saturateLatch.countDown();
                });
            }
            saturateLatch.await(5, TimeUnit.SECONDS); // wait for them to be admitted & executing
            // TPS window is now full — all further tasks must queue

            // Now submit LOW-priority tasks; they will queue because TPS is saturated
            var latch = new CountDownLatch(LOW + HIGH);
            for (int i = 0; i < LOW; i++) {
                int id = i;
                exec.submit(ctx("NORTH_AMERICA", "GOLD", 1000, 1), () -> {
                    execOrder.add(-(id + 1)); // negative = low priority
                    latch.countDown();
                    return null;
                });
            }

            // Submit HIGH-priority tasks; they also queue, but with higher PriorityKey
            for (int i = 0; i < HIGH; i++) {
                int id = i;
                exec.submit(ctx("NORTH_AMERICA", "PLATINUM", 200000, 100 - i), () -> {
                    execOrder.add(id + 1); // positive = high priority
                    latch.countDown();
                    return null;
                });
            }

            boolean ok = latch.await(30, TimeUnit.SECONDS);
            long highInFirstHalf = execOrder.stream().limit(HIGH).filter(v -> v > 0).count();
            double ratio = HIGH > 0 ? (double) highInFirstHalf / HIGH : 0;

            String pass = ok && ratio > 0.5 ? "PASS" : "FAIL";
            row(scenario, "total completed",          String.valueOf(execOrder.size()), pass);
            row(scenario, "high-prio in first half",  highInFirstHalf + "/" + HIGH, pass);
            row(scenario, "high-priority ratio",      pct(ratio), pass);
            row(scenario, "ordering correct (>50%)",  ratio > 0.5 ? "YES" : "NO", pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 5 — Concurrent thread storm
    // -----------------------------------------------------------------------
    static void lt05(String scenario) throws Exception {
        int THREADS = 50, PER = 20, TOTAL = THREADS * PER;
        var exec = buildExecutor(makeConfig(500, 300, 200));
        try {
            var done = new AtomicInteger();
            var errors = new AtomicInteger();
            var startGun = new CountDownLatch(1);
            var finished = new CountDownLatch(TOTAL);
            var submitters = Executors.newFixedThreadPool(THREADS);

            for (int t = 0; t < THREADS; t++) {
                int tid = t;
                submitters.submit(() -> {
                    try { startGun.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    String region = tid % 3 == 0 ? "NORTH_AMERICA" : tid % 3 == 1 ? "EUROPE" : "ASIA";
                    String tier   = tid % 2 == 0 ? "PLATINUM" : "GOLD";
                    for (int i = 0; i < PER; i++) {
                        try {
                            exec.submit(ctx(region, tier, 1000 * tid, i), () -> { done.incrementAndGet(); finished.countDown(); });
                        } catch (Exception e) { errors.incrementAndGet(); finished.countDown(); }
                    }
                });
            }

            long start = System.nanoTime();
            startGun.countDown();
            boolean ok = finished.await(30, TimeUnit.SECONDS);
            double elapsedS = (System.nanoTime() - start) / 1e9;
            submitters.shutdownNow();

            String pass = ok && done.get() >= TOTAL * 0.99 ? "PASS" : "FAIL";
            row(scenario, "threads × tasks/thread",  THREADS + " × " + PER, pass);
            row(scenario, "completed",               String.valueOf(done.get()), pass);
            row(scenario, "submission errors",       String.valueOf(errors.get()), pass);
            row(scenario, "elapsed",                 fmt2(elapsedS) + " s", pass);
            row(scenario, "effective TPS",           fmtK(done.get() / elapsedS) + " TPS", pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 6 — Routing accuracy
    // -----------------------------------------------------------------------
    static void lt06(String scenario) throws Exception {
        int PER = 50;
        var cfg = makeConfig(2000, 1000, 1000);
        var pe  = PolicyEngineFactory.create(cfg);
        var exec = buildExecutor(cfg, pe);
        try {
            record Route(String region, String tier, int amount, String expected) {}
            var routes = List.of(
                new Route("NORTH_AMERICA", "PLATINUM", 200000, "fast"),
                new Route("NORTH_AMERICA", "GOLD",     5000,   "fast"),
                new Route("NORTH_AMERICA", "STANDARD", 1000,   "bulk"),
                new Route("EUROPE",        "GOLD",     5000,   "fast"),
                new Route("ASIA",          "GOLD",     5000,   "bulk"),
                new Route("ASIA",          "STANDARD", 500,    "bulk")
            );

            var wrong = new AtomicInteger();
            int total = routes.size() * PER;
            var latch = new CountDownLatch(total);

            for (var r : routes) {
                for (int i = 0; i < PER; i++) {
                    var c = ctx(r.region(), r.tier(), r.amount(), i);
                    String actual = pe.evaluate(c).getMatchedPath().executor();
                    if (!actual.equals(r.expected())) wrong.incrementAndGet();
                    exec.submit(c, latch::countDown);
                }
            }
            latch.await(15, TimeUnit.SECONDS);

            String pass = wrong.get() == 0 ? "PASS" : "FAIL";
            row(scenario, "routes tested",   String.valueOf(routes.size()), pass);
            row(scenario, "tasks submitted", String.valueOf(total), pass);
            row(scenario, "routing errors",  String.valueOf(wrong.get()), pass);
            row(scenario, "accuracy",        wrong.get() == 0 ? "100%" : pct(1.0 - (double) wrong.get() / total), pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 7 — TPS gate max-overshoot sweep
    // Hammer the gate with increasing thread counts to find peak overshoot.
    // Race condition: multiple threads pass hasCapacity() before any calls tryAdd(),
    // so all of them get admitted → overshoot. More threads = more races in flight.
    // -----------------------------------------------------------------------
    static void lt07(String scenario) throws Exception {
        int   CONFIGURED_TPS = 500;
        int   WINDOW_MS      = 2000; // measure each thread-count for 2 s steady-state
        int[] THREAD_COUNTS  = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512};

        // fast=100 is the bottleneck; main/bulk are loose
        var cfg  = makeConfig(10_000_000, CONFIGURED_TPS, 5_000_000);
        var exec = buildExecutor(cfg);

        double peakOvershootPct = 0;
        int    peakThreads      = 0;
        int    peakAvgTps       = 0;

        try {
            for (int threads : THREAD_COUNTS) {
                var admitted = new AtomicInteger();
                var stop     = new AtomicBoolean(false);
                var hammers  = Executors.newFixedThreadPool(threads);
                var startGun = new CountDownLatch(1);

                for (int t = 0; t < threads; t++) {
                    hammers.submit(() -> {
                        try { startGun.await(); } catch (InterruptedException e) { return; }
                        while (!stop.get()) {
                            try {
                                exec.submit(ctx("NORTH_AMERICA", "GOLD", 5000, 1), () -> {});
                                admitted.incrementAndGet();
                            } catch (com.pool.exception.TaskRejectedException ignored) {}
                        }
                    });
                }

                // Warm-up: 500 ms (let sliding window stabilise)
                startGun.countDown();
                Thread.sleep(500);
                admitted.set(0); // reset — only count steady-state

                // Measure window
                Thread.sleep(WINDOW_MS);
                int total = admitted.get();
                stop.set(true);
                hammers.shutdownNow();
                hammers.awaitTermination(1, TimeUnit.SECONDS);

                double avgTps       = (double) total / (WINDOW_MS / 1000.0);
                double overshootPct = (avgTps - CONFIGURED_TPS) / CONFIGURED_TPS * 100.0;

                if (overshootPct > peakOvershootPct) {
                    peakOvershootPct = overshootPct;
                    peakThreads      = threads;
                    peakAvgTps       = (int) Math.round(avgTps);
                }

                String tag = String.format("%3d threads", threads);
                String pct = (overshootPct >= 0 ? "+" : "") + fmt1(overshootPct) + "%";
                row(scenario, tag + "  avg TPS", String.valueOf((int) Math.round(avgTps)), "INFO");
                row(scenario, tag + "  overshoot", pct, "INFO");
            }

            String pass = peakOvershootPct > 0 ? "PASS" : "FAIL";
            row(scenario, "─── configured TPS",  String.valueOf(CONFIGURED_TPS), pass);
            row(scenario, "─── peak overshoot at", peakThreads + " threads", pass);
            row(scenario, "─── peak avg TPS",    String.valueOf(peakAvgTps), pass);
            row(scenario, "─── peak overshoot %", "+" + fmt1(peakOvershootPct) + "%", pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 8 — Queue overflow
    // -----------------------------------------------------------------------
    static void lt08(String scenario) throws Exception {
        // fast: TPS=5, queue cap=20 → 200 tasks will overflow heavily
        var executors = List.of(
            ExecutorSpec.root("main", 1000, 10000, null),
            ExecutorSpec.child("fast", "main", 5, 20, null),
            ExecutorSpec.child("bulk", "main", 100, 500, null)
        );
        var cfg = new PoolConfig();
        cfg.setName("overflow-test"); cfg.setVersion("1.0");
        var adapters = new AdaptersConfig();
        adapters.setExecutors(new ArrayList<>(executors));
        cfg.setAdapters(adapters);
        cfg.setPriorityTree(new ArrayList<>(List.of(
            node("L1.ALL", "true", null, null, List.of(
                leaf("L2.FAST", "true", SortByConfig.fifo(), "fast")
            ))
        )));
        cfg.setPriorityStrategy(StrategyConfig.fifo());
        var exec = buildExecutor(cfg, PolicyEngineFactory.create(cfg));

        try {
            int TASKS = 200;
            var done     = new AtomicInteger();
            var rejected = new AtomicInteger();
            var latch    = new CountDownLatch(TASKS);

            for (int i = 0; i < TASKS; i++) {
                var c = ctx("EUROPE", "GOLD", 1000, 1);
                try {
                    exec.submit(c, () -> { done.incrementAndGet(); latch.countDown(); });
                } catch (TaskRejectedException e) {
                    rejected.incrementAndGet(); latch.countDown();
                }
            }
            int queuePeak = exec.getQueueSize("fast");
            latch.await(10, TimeUnit.SECONDS);

            String pass = (done.get() + rejected.get()) >= TASKS * 0.99 ? "PASS" : "FAIL";
            row(scenario, "submitted",          String.valueOf(TASKS), pass);
            row(scenario, "completed",          String.valueOf(done.get()), pass);
            row(scenario, "rejected (overflow)", String.valueOf(rejected.get()), pass);
            row(scenario, "fast queue cap",     "20", pass);
            row(scenario, "queue peak observed", String.valueOf(queuePeak), pass);
            row(scenario, "overflow handled",   rejected.get() > 0 || queuePeak <= 20 ? "YES" : "NO", pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 9 — Expression evaluation throughput
    // -----------------------------------------------------------------------
    static void lt09(String scenario) {
        var cfg = makeConfig(100000, 50000, 30000);
        var pe  = PolicyEngineFactory.create(cfg);

        int EVALS = 5000;
        String[] regions = {"NORTH_AMERICA", "EUROPE", "ASIA"};
        String[] tiers   = {"PLATINUM", "GOLD", "STANDARD"};
        int[]    amounts = {200000, 50000, 5000, 500};

        long start = System.nanoTime();
        for (int i = 0; i < EVALS; i++) {
            pe.evaluate(ctx(regions[i % 3], tiers[i % 3], amounts[i % 4], i % 100));
        }
        double elapsedMs = (System.nanoTime() - start) / 1e6;
        double perSec    = EVALS / (elapsedMs / 1000.0);
        double avgUs     = elapsedMs * 1000 / EVALS;

        String pass = perSec > 5000 ? "PASS" : "WARN";
        row(scenario, "evaluations",      String.valueOf(EVALS), pass);
        row(scenario, "elapsed",          fmt1(elapsedMs) + " ms", pass);
        row(scenario, "avg per eval",     fmt1(avgUs) + " µs", pass);
        row(scenario, "evals / sec",      fmtK(perSec), pass);
        row(scenario, "target",           "> 5 000 / sec", pass);
        pass(pass);
    }

    // -----------------------------------------------------------------------
    // SCENARIO 10 — Mixed workload
    // -----------------------------------------------------------------------
    static void lt10(String scenario) throws Exception {
        int TASKS = 600;
        var cfg  = makeConfig(300, 200, 100);
        var pe   = PolicyEngineFactory.create(cfg);
        var exec = buildExecutor(cfg, pe);
        try {
            var done     = new AtomicInteger();
            var fastDone = new AtomicInteger();
            var bulkDone = new AtomicInteger();
            var latch    = new CountDownLatch(TASKS);

            String[] regions = {"NORTH_AMERICA", "EUROPE", "ASIA"};
            String[] tiers   = {"PLATINUM", "GOLD", "STANDARD", "BRONZE"};
            int[]    amounts = {500000, 150000, 50000, 5000, 500};

            long start = System.nanoTime();
            for (int i = 0; i < TASKS; i++) {
                String region = regions[i % regions.length];
                String tier   = tiers[i % tiers.length];
                int    amount = amounts[i % amounts.length];
                boolean isFast = "fast".equals(pe.evaluate(ctx(region, tier, amount, i % 10)).getMatchedPath().executor());
                exec.submit(ctx(region, tier, amount, i % 10), () -> {
                    done.incrementAndGet();
                    if (isFast) fastDone.incrementAndGet(); else bulkDone.incrementAndGet();
                    latch.countDown();
                });
            }

            boolean ok = latch.await(30, TimeUnit.SECONDS);
            double elapsedS = (System.nanoTime() - start) / 1e9;

            TpsPoolExecutor.TpsExecutorStats stats = exec.getStats();
            String pass = ok && done.get() == TASKS ? "PASS" : "FAIL";
            row(scenario, "submitted / completed", TASKS + " / " + done.get(), pass);
            row(scenario, "→ fast executor",       String.valueOf(fastDone.get()), pass);
            row(scenario, "→ bulk executor",       String.valueOf(bulkDone.get()), pass);
            row(scenario, "elapsed",               fmt2(elapsedS) + " s", pass);
            row(scenario, "stats rejected",        String.valueOf(stats.rejected()), pass);
            pass(pass);
        } finally { exec.shutdownNow(); }
    }

    // -----------------------------------------------------------------------
    // SCENARIO 11 — Graceful shutdown
    // -----------------------------------------------------------------------
    static void lt11(String scenario) throws Exception {
        int TASKS = 30;
        var exec = buildExecutor(makeConfig(1000, 500, 300));
        var done = new AtomicInteger();
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < TASKS; i++) {
            exec.submit(ctx("NORTH_AMERICA", "GOLD", 5000, i), () -> {
                Thread.sleep(20);
                return done.incrementAndGet();
            });
        }
        exec.shutdown();
        boolean terminated = exec.awaitTermination(15, TimeUnit.SECONDS);

        String pass = terminated && done.get() == TASKS ? "PASS" : "FAIL";
        row(scenario, "tasks submitted",    String.valueOf(TASKS), pass);
        row(scenario, "completed before shutdown", String.valueOf(done.get()), pass);
        row(scenario, "terminated cleanly", String.valueOf(terminated), pass);
        pass(pass);
    }

    // -----------------------------------------------------------------------
    // Harness helpers
    // -----------------------------------------------------------------------
    @FunctionalInterface
    interface Scenario { void run(String name) throws Exception; }

    static void run(String name, Scenario s) {
        scenariosRun++;
        System.out.printf("  %-62s", name + " ...");
        System.out.flush();
        try {
            s.run(name);
            System.out.println(" done");
        } catch (Exception e) {
            System.out.println(" ERROR: " + e.getMessage());
            row(name, "exception", e.getClass().getSimpleName() + ": " + e.getMessage(), "FAIL");
            scenariosRun--; // will re-count in pass()
            pass("FAIL");
        }
    }

    static void pass(String status) {
        if ("PASS".equals(status) || "WARN".equals(status)) scenariosPassed++;
    }

    static void row(String scenario, String metric, String value, String status) {
        ROWS.add(new Row(scenario, metric, value, status));
    }

    // -----------------------------------------------------------------------
    // Table printer
    // -----------------------------------------------------------------------
    static void printTable() {
        int S = 42, M = 26, V = 18, T = 7;
        String sep = "+" + "-".repeat(S+2) + "+" + "-".repeat(M+2) + "+" + "-".repeat(V+2) + "+" + "-".repeat(T+2) + "+";
        System.out.println();
        System.out.println(sep);
        System.out.printf("| %-"+S+"s | %-"+M+"s | %-"+V+"s | %-"+T+"s |%n",
                "Scenario", "Metric", "Value", "Status");
        System.out.println(sep);

        String lastScenario = "";
        for (Row r : ROWS) {
            String scenarioDisplay = r.scenario().equals(lastScenario) ? "" : r.scenario();
            lastScenario = r.scenario();
            String statusStr = switch (r.status()) {
                case "PASS" -> "PASS ✓";
                case "FAIL" -> "FAIL ✗";
                case "WARN" -> "WARN ⚠";
                case "INFO" -> "     ";
                default -> r.status();
            };
            System.out.printf("| %-"+S+"s | %-"+M+"s | %-"+V+"s | %-"+T+"s |%n",
                    trunc(scenarioDisplay, S),
                    trunc(r.metric(), M),
                    trunc(r.value(), V),
                    statusStr);
        }
        System.out.println(sep);
        System.out.printf("%n  Result: %d / %d scenarios passed%n%n", scenariosPassed, scenariosRun);
    }

    // -----------------------------------------------------------------------
    // Library wiring (same pattern as PoolApplicationTest)
    // -----------------------------------------------------------------------
    static TpsPoolExecutor buildExecutor(PoolConfig config) {
        return buildExecutor(config, PolicyEngineFactory.create(config));
    }

    static TpsPoolExecutor buildExecutor(PoolConfig config, PolicyEngine pe) {
        ExecutorHierarchy hierarchy = new ExecutorHierarchy(config.getExecutors());
        TpsGate tpsGate = new TpsGate(hierarchy);
        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        Map<String, java.util.concurrent.locks.ReentrantLock> locks = new ConcurrentHashMap<>();
        Map<String, java.util.concurrent.locks.Condition>    conds  = new ConcurrentHashMap<>();
        Map<String, com.pool.strategy.PriorityStrategy<TaskQueueManager.QueuedTask>> strategies = new ConcurrentHashMap<>();

        for (String id : hierarchy.getAllExecutorIds()) {
            var lock = new java.util.concurrent.locks.ReentrantLock();
            locks.put(id, lock);
            conds.put(id, lock.newCondition());
            int cap = hierarchy.getQueueCapacity(id);
            strategies.put(id, PriorityStrategyFactory.createDefault(cap <= 0 ? Integer.MAX_VALUE : cap));
            SlidingWindowCounter counter = tpsGate.getCounter(id);
            if (counter != null) {
                var l = lock; var c = conds.get(id);
                counter.setOnEviction(() -> { l.lock(); try { c.signalAll(); } finally { l.unlock(); } });
            }
        }
        return new TpsPoolExecutor(config, pe, hierarchy, tpsGate,
                new TaskQueueManager(hierarchy, tpsGate, strategies, locks, conds, threadPool));
    }

    // -----------------------------------------------------------------------
    // Config / context builders
    // -----------------------------------------------------------------------
    static PoolConfig makeConfig(int mainTps, int fastTps, int bulkTps) {
        var cfg = new PoolConfig();
        cfg.setName("local-load-test"); cfg.setVersion("1.0");
        var adapters = new AdaptersConfig();
        adapters.setExecutors(new ArrayList<>(List.of(
            ExecutorSpec.root("main", mainTps, 10000, null),
            ExecutorSpec.child("fast", "main", fastTps, 5000, null),
            ExecutorSpec.child("bulk", "main", bulkTps, 3000, null)
        )));
        cfg.setAdapters(adapters);
        cfg.setPriorityTree(new ArrayList<>(List.of(
            node("L1.NA",  "$req.region == \"NORTH_AMERICA\"", null, null, List.of(
                node("L2.PLAT", "$req.customerTier == \"PLATINUM\"", null, null, List.of(
                    leaf("L3.HI",  "$req.transactionAmount > 100000", sortBy("$req.priority", SortDirection.DESC), "fast"),
                    leaf("L3.DEF", "true", sortBy("$req.urgency", SortDirection.DESC), "fast")
                )),
                node("L2.GOLD", "$req.customerTier == \"GOLD\"", null, null, List.of(
                    leaf("L3.DEF", "true", SortByConfig.fifo(), "fast")
                )),
                node("L2.DEF", "true", null, null, List.of(
                    leaf("L3.DEF", "true", SortByConfig.fifo(), "bulk")
                ))
            )),
            node("L1.EU",  "$req.region == \"EUROPE\"", null, null, List.of(
                node("L2.DEF", "true", null, null, List.of(
                    leaf("L3.DEF", "true", sortBy("$req.priority", SortDirection.DESC), "fast")
                ))
            )),
            node("L1.DEF", "true", null, null, List.of(
                node("L2.DEF", "true", null, null, List.of(
                    leaf("L3.DEF", "true", SortByConfig.fifo(), "bulk")
                ))
            ))
        )));
        cfg.setPriorityStrategy(StrategyConfig.fifo());
        return cfg;
    }

    static TaskContext ctx(String region, String tier, int amount, int priority) {
        return TaskContextFactory.create(
            "{\"region\":\"%s\",\"customerTier\":\"%s\",\"transactionAmount\":%d,\"priority\":%d,\"urgency\":%d}"
                .formatted(region, tier, amount, priority, priority),
            Map.of("clientId", "load-test"));
    }

    static PriorityNodeConfig leaf(String name, String cond, SortByConfig sort, String exec) {
        var n = new PriorityNodeConfig();
        n.setName(name); n.setCondition(cond); n.setSortBy(sort); n.setExecutor(exec); return n;
    }

    static PriorityNodeConfig node(String name, String cond, SortByConfig sort, String exec, List<PriorityNodeConfig> nested) {
        var n = new PriorityNodeConfig();
        n.setName(name); n.setCondition(cond); n.setSortBy(sort); n.setExecutor(exec);
        n.setNestedLevels(new ArrayList<>(nested)); return n;
    }

    static SortByConfig sortBy(String field, SortDirection dir) {
        var s = new SortByConfig(); s.setField(field); s.setDirection(dir); return s;
    }

    // -----------------------------------------------------------------------
    // Formatting
    // -----------------------------------------------------------------------
    static String fmt1(double v)  { return String.format("%.1f", v); }
    static String fmt2(double v)  { return String.format("%.2f", v); }
    static String fmtK(double v)  { return v >= 1000 ? String.format("%.0f", v) : String.format("%.1f", v); }
    static String pct(double v)   { return String.format("%.0f%%", v * 100); }
    static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
    static void banner(String title) {
        int w = 68;
        System.out.println("=".repeat(w));
        System.out.printf("  %s%n", title);
        System.out.println("=".repeat(w));
    }
}
