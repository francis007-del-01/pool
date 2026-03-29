package com.pool.aspect;

import com.pool.adapter.executor.tps.TaskQueueManager;
import com.pool.adapter.executor.tps.TpsGate;
import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.annotation.PoolContextBuilder;
import com.pool.annotation.Pooled;
import com.pool.config.*;
import com.pool.core.TpsContext;
import com.pool.core.TpsCounter;
import com.pool.exception.TpsExceededException;
import com.pool.policy.PolicyEngine;
import com.pool.policy.PolicyEngineFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RequestInterceptorAspect.
 * Uses Spring AOP's AspectJProxyFactory for unit-test-level proxying.
 */
class RequestInterceptorAspectTest {

    private TpsPoolExecutor tpsPoolExecutor;
    private RequestInterceptorAspect aspect;
    private ApplicationContext appContext;

    @BeforeEach
    void setUp() {
        PoolConfig config = createTestConfig();
        tpsPoolExecutor = buildExecutor(config);
        appContext = mock(ApplicationContext.class);
        when(appContext.getBean(OrderBuilder.class)).thenReturn(new OrderBuilder());
        when(appContext.getBean(MultiContextBuilder.class)).thenReturn(new MultiContextBuilder());
        when(appContext.getBean(CompareBuilder.class)).thenReturn(new CompareBuilder());
        aspect = new RequestInterceptorAspect(tpsPoolExecutor, new ObjectMapper(), appContext);
    }

    @AfterEach
    void tearDown() {
        if (tpsPoolExecutor != null && !tpsPoolExecutor.isShutdown()) {
            tpsPoolExecutor.shutdownNow();
        }
        TpsContext.clear();
    }

    @Test
    @DisplayName("Proxied call should be admitted when under TPS limit")
    void shouldAdmitWhenUnderLimit() {
        SampleService proxy = createProxy(new SampleService());

        String result = proxy.doWork("hello");
        assertEquals("processed: hello", result);
    }

    @Test
    @DisplayName("Multiple calls should be admitted up to TPS limit")
    void shouldAdmitUpToLimit() {
        SampleService proxy = createProxy(new SampleService());

        for (int i = 0; i < 10; i++) {
            String result = proxy.doWork("call-" + i);
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("TpsContext should bypass gate for downstream calls")
    void tpsContextShouldBypass() {
        SampleService proxy = createProxy(new SampleService());

        TpsContext.markProcessed();
        try {
            for (int i = 0; i < 1000; i++) {
                proxy.doWork("bypass-" + i);
            }
        } finally {
            TpsContext.clear();
        }
    }

    @Test
    @DisplayName("TpsContext should be cleared after call completes")
    void tpsContextClearedAfterCall() {
        SampleService proxy = createProxy(new SampleService());

        assertFalse(TpsContext.isProcessed());
        proxy.doWork("test");
        // TpsContext is set on the pool thread, not the caller thread
        assertFalse(TpsContext.isProcessed());
    }

    @Test
    @DisplayName("TpsContext should be cleared even if exception thrown")
    void tpsContextClearedOnException() {
        FailingService proxy = createProxy(new FailingService());

        assertFalse(TpsContext.isProcessed());
        assertThrows(RuntimeException.class, proxy::doWork);
        assertFalse(TpsContext.isProcessed());
    }

    @Test
    @DisplayName("Should timeout when TPS exhausted and queue timeout expires")
    void shouldTimeoutWhenTpsExhausted() {
        PoolConfig config = createRestrictiveConfig(1);
        TpsPoolExecutor restrictedExecutor = buildExecutor(config, 60000);

        RequestInterceptorAspect restrictedAspect = new RequestInterceptorAspect(
                restrictedExecutor, new ObjectMapper(), mock(ApplicationContext.class));

        SampleServiceWithTimeout rawService = new SampleServiceWithTimeout();
        AspectJProxyFactory factory = new AspectJProxyFactory(rawService);
        factory.addAspect(restrictedAspect);
        SampleServiceWithTimeout proxy = factory.getProxy();

        // First call succeeds (uses the 1 TPS slot)
        proxy.doWork("first");

        // Second call should timeout because TPS is exhausted and window is 60s
        assertThrows(TpsExceededException.class, () -> proxy.doWork("second"));

        restrictedExecutor.shutdownNow();
    }

    // ---- helpers ----

    private <T> T createProxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private static TpsPoolExecutor buildExecutor(PoolConfig config) {
        return buildExecutor(config, 1000);
    }

    private static TpsPoolExecutor buildExecutor(PoolConfig config, long windowSizeMs) {
        ExecutorHierarchy hierarchy = new ExecutorHierarchy(config.getExecutors());
        TpsGate tpsGate = new TpsGate(hierarchy, windowSizeMs);
        PolicyEngine policyEngine = PolicyEngineFactory.create(config);

        Map<String, java.util.concurrent.locks.ReentrantLock> locks = new ConcurrentHashMap<>();
        Map<String, java.util.concurrent.locks.Condition> conditions = new ConcurrentHashMap<>();
        Map<String, com.pool.strategy.PriorityStrategy<TaskQueueManager.QueuedTask>> strategies = new ConcurrentHashMap<>();

        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        for (String rootId : hierarchy.getRootIds()) {
            var lock = new java.util.concurrent.locks.ReentrantLock();
            locks.put(rootId, lock);
            conditions.put(rootId, lock.newCondition());
            int cap = hierarchy.getQueueCapacity(rootId);
            strategies.put(rootId, com.pool.strategy.PriorityStrategyFactory.createDefault(cap));
        }

        for (String id : hierarchy.getAllExecutorIds()) {
            TpsCounter counter = tpsGate.getCounter(id);
            if (counter != null) {
                String rootId = hierarchy.getRootIdFor(id);
                var l = locks.get(rootId);
                var c = conditions.get(rootId);
                counter.setOnReset(() -> { l.lock(); try { c.signalAll(); } finally { l.unlock(); } });
            }
        }

        TaskQueueManager queueManager = new TaskQueueManager(
                hierarchy, tpsGate, strategies, locks, conditions, threadPool);

        return new TpsPoolExecutor(config, policyEngine, hierarchy, tpsGate, queueManager);
    }

    private PoolConfig createTestConfig() {
        List<ExecutorSpec> executors = new ArrayList<>(List.of(
                ExecutorSpec.root("main", 1000, 5000)
        ));

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
        config.setPriorityTree(new ArrayList<>(List.of(defaultNode)));
        config.setPriorityStrategy(StrategyConfig.fifo());
        return config;
    }

    private PoolConfig createRestrictiveConfig(int maxTps) {
        List<ExecutorSpec> executors = new ArrayList<>(List.of(
                ExecutorSpec.root("main", maxTps, 100)
        ));

        PriorityNodeConfig defaultNode = new PriorityNodeConfig();
        defaultNode.setName("DEFAULT");
        defaultNode.setCondition("true");
        defaultNode.setSortBy(SortByConfig.fifo());
        defaultNode.setExecutor("main");

        PoolConfig config = new PoolConfig();
        config.setName("restrictive-pool");
        config.setVersion("1.0");
        AdaptersConfig adapters = new AdaptersConfig();
        adapters.setExecutors(executors);
        config.setAdapters(adapters);
        config.setPriorityTree(new ArrayList<>(List.of(defaultNode)));
        config.setPriorityStrategy(StrategyConfig.fifo());
        return config;
    }

    @Test
    @DisplayName("PoolContextBuilder called and result serialized as request context")
    void contextBuilderInvoked() {
        OrderService proxy = createProxy(new OrderService());
        String result = proxy.process(new OrderRequest("ORD-1", 500.0));
        assertEquals("order: ORD-1", result);
    }

    @Test
    @DisplayName("Builder receiving multiple args composes context correctly")
    void multiArgBuilder() {
        MultiContextService proxy = createProxy(new MultiContextService());
        String result = proxy.process(new OrderRequest("ORD-2", 200.0), new CustomerInfo("PLATINUM"));
        assertEquals("order: ORD-2 tier: PLATINUM", result);
    }

    @Test
    @DisplayName("Builder receiving two args of same type composes context correctly")
    void sameTypeMultipleArgs() {
        CompareService proxy = createProxy(new CompareService());
        String result = proxy.compare(new OrderRequest("ORD-A", 100.0), new OrderRequest("ORD-B", 200.0));
        assertEquals("before: ORD-A after: ORD-B", result);
    }

    // ---- test service classes ----

    @Pooled
    public static class SampleService {
        public String doWork(String input) {
            return "processed: " + input;
        }
    }

    @Pooled(timeoutMs = 100)
    public static class SampleServiceWithTimeout {
        public String doWork(String input) {
            return "processed: " + input;
        }
    }

    @Pooled
    public static class FailingService {
        public void doWork() {
            throw new RuntimeException("intentional failure");
        }
    }

    @Pooled(contextType = OrderBuilder.class)
    public static class OrderService {
        public String process(OrderRequest order) {
            return "order: " + order.orderId();
        }
    }

    @Pooled(contextType = MultiContextBuilder.class)
    public static class MultiContextService {
        public String process(OrderRequest order, CustomerInfo customer) {
            return "order: " + order.orderId() + " tier: " + customer.tier();
        }
    }

    @Pooled(contextType = CompareBuilder.class)
    public static class CompareService {
        public String compare(OrderRequest before, OrderRequest after) {
            return "before: " + before.orderId() + " after: " + after.orderId();
        }
    }

    // ---- context builders ----

    public static class OrderBuilder implements PoolContextBuilder {
        @Override
        public Object build(Object[] args) {
            OrderRequest req = (OrderRequest) args[0];
            return Map.of("orderId", req.orderId(), "amount", req.amount());
        }
    }

    public static class MultiContextBuilder implements PoolContextBuilder {
        @Override
        public Object build(Object[] args) {
            OrderRequest order = (OrderRequest) args[0];
            CustomerInfo customer = (CustomerInfo) args[1];
            return Map.of("orderId", order.orderId(), "tier", customer.tier());
        }
    }

    public static class CompareBuilder implements PoolContextBuilder {
        @Override
        public Object build(Object[] args) {
            OrderRequest before = (OrderRequest) args[0];
            OrderRequest after = (OrderRequest) args[1];
            return Map.of("before", before.orderId(), "after", after.orderId());
        }
    }

    record OrderRequest(String orderId, double amount) {}
    record CustomerInfo(String tier) {}
}
