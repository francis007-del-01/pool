package com.pool.scheduler;

import com.pool.config.PoolConfig;
import com.pool.config.QueueConfig;
import com.pool.config.SchedulerConfig;
import com.pool.core.PrioritizedPayload;
import com.pool.core.TaskContext;
import com.pool.policy.DefaultPolicyEngine;
import com.pool.policy.EvaluationResult;
import com.pool.policy.MatchedPath;
import com.pool.policy.PolicyEngine;
import com.pool.strategy.PriorityStrategy;
import com.pool.strategy.PriorityStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory priority scheduler supporting multiple named queues.
 * Evaluates policy and orders payloads without executing tasks.
 */
public class DefaultPriorityScheduler<T> implements PriorityScheduler<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultPriorityScheduler.class);

    private final PolicyEngine policyEngine;
    private final Map<String, PriorityStrategy> queues;
    private final List<String> queueOrder; // sorted by index (lowest first)
    private final String defaultQueueName;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final Object monitor = new Object();

    public DefaultPriorityScheduler(PoolConfig config) {
        this(config, new DefaultPolicyEngine(config));
    }

    public DefaultPriorityScheduler(PoolConfig config, PolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
        
        SchedulerConfig schedulerConfig = config.scheduler() != null 
                ? config.scheduler() 
                : SchedulerConfig.defaults();
        
        // Create queues from config
        this.queues = new LinkedHashMap<>();
        List<QueueConfig> sortedQueues = schedulerConfig.queues().stream()
                .sorted(Comparator.comparingInt(QueueConfig::index))
                .toList();
        
        for (QueueConfig qc : sortedQueues) {
            PriorityStrategy strategy = PriorityStrategyFactory.create(
                    config.priorityStrategy(),
                    qc.capacity()
            );
            queues.put(qc.name(), strategy);
            log.info("Created queue '{}' (index={}, capacity={})", qc.name(), qc.index(), qc.capacity());
        }
        
        this.queueOrder = new ArrayList<>(queues.keySet());
        this.defaultQueueName = queueOrder.isEmpty() ? null : queueOrder.get(0);
        
        log.info("DefaultPriorityScheduler initialized with {} queues, default='{}'", 
                queues.size(), defaultQueueName);
    }

    @Override
    public String submit(TaskContext context, T payload) {
        if (context == null) {
            throw new NullPointerException("Context cannot be null");
        }
        if (shutdown.get()) {
            log.warn("Scheduler is shutdown, rejecting task: {}", context.getTaskId());
            return null;
        }

        // Evaluate policy to get priority and target queue
        EvaluationResult result = policyEngine.evaluate(context);
        String queueName = resolveQueueName(result);
        
        PriorityStrategy strategy = queues.get(queueName);
        if (strategy == null) {
            log.error("Queue '{}' not found, rejecting task: {}", queueName, context.getTaskId());
            return null;
        }

        PrioritizedPayload<T> item = new PrioritizedPayload<>(
                payload,
                context.getTaskId(),
                result.getPriorityKey()
        );
        
        boolean enqueued = strategy.enqueue(item);
        if (enqueued) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
            log.debug("Task {} enqueued to '{}' (size={})", 
                    context.getTaskId(), queueName, strategy.getQueueSize());
            return queueName;
        }
        
        log.warn("Queue '{}' is full, rejecting task: {}", queueName, context.getTaskId());
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNext() throws InterruptedException {
        while (!shutdown.get()) {
            // Try each queue in order
            for (String queueName : queueOrder) {
                PriorityStrategy strategy = queues.get(queueName);
                Optional<PrioritizedPayload<?>> item = strategy.pollNext();
                if (item.isPresent()) {
                    return ((PrioritizedPayload<T>) item.get()).getPayload();
                }
            }
            // All queues empty, wait
            synchronized (monitor) {
                monitor.wait();
            }
        }
        throw new InterruptedException("Scheduler is shutdown");
    }

    @Override
    public Optional<T> getNext(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        
        while (!shutdown.get()) {
            // Try each queue in order
            for (String queueName : queueOrder) {
                PriorityStrategy strategy = queues.get(queueName);
                Optional<PrioritizedPayload<?>> item = strategy.pollNext();
                if (item.isPresent()) {
                    @SuppressWarnings("unchecked")
                    PrioritizedPayload<T> typed = (PrioritizedPayload<T>) item.get();
                    return Optional.of(typed.getPayload());
                }
            }
            
            // All queues empty, wait with remaining timeout
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Optional.empty();
            }
            
            synchronized (monitor) {
                long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
                monitor.wait(waitMillis);
            }
        }
        return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNext(String queueName) throws InterruptedException {
        PriorityStrategy strategy = queues.get(queueName);
        if (strategy == null) {
            throw new IllegalArgumentException("Queue not found: " + queueName);
        }
        
        while (!shutdown.get()) {
            Optional<PrioritizedPayload<?>> item = strategy.pollNext();
            if (item.isPresent()) {
                return ((PrioritizedPayload<T>) item.get()).getPayload();
            }
            synchronized (monitor) {
                monitor.wait();
            }
        }
        throw new InterruptedException("Scheduler is shutdown");
    }

    @Override
    public Optional<T> getNext(String queueName, long timeout, TimeUnit unit) throws InterruptedException {
        PriorityStrategy strategy = queues.get(queueName);
        if (strategy == null) {
            throw new IllegalArgumentException("Queue not found: " + queueName);
        }
        
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        
        while (!shutdown.get()) {
            Optional<PrioritizedPayload<?>> item = strategy.pollNext();
            if (item.isPresent()) {
                @SuppressWarnings("unchecked")
                PrioritizedPayload<T> typed = (PrioritizedPayload<T>) item.get();
                return Optional.of(typed.getPayload());
            }
            
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Optional.empty();
            }
            
            synchronized (monitor) {
                long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
                monitor.wait(waitMillis);
            }
        }
        return Optional.empty();
    }

    @Override
    public int size() {
        return queues.values().stream().mapToInt(PriorityStrategy::getQueueSize).sum();
    }

    @Override
    public int size(String queueName) {
        PriorityStrategy strategy = queues.get(queueName);
        return strategy != null ? strategy.getQueueSize() : 0;
    }

    @Override
    public int remainingCapacity() {
        return queues.values().stream().mapToInt(PriorityStrategy::getRemainingCapacity).sum();
    }

    @Override
    public void shutdown() {
        shutdown.set(true);
        for (PriorityStrategy strategy : queues.values()) {
            strategy.shutdown();
        }
        synchronized (monitor) {
            monitor.notifyAll();
        }
        log.info("DefaultPriorityScheduler shutdown, {} items remaining", size());
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Resolve target queue name from evaluation result.
     * Falls back to default queue if not specified.
     */
    private String resolveQueueName(EvaluationResult result) {
        if (result != null && result.getMatchedPath() != null) {
            MatchedPath path = result.getMatchedPath();
            String queueName = path.queueName();
            if (queueName != null && !queueName.isBlank() && queues.containsKey(queueName)) {
                return queueName;
            }
        }
        return defaultQueueName;
    }

    /**
     * Get all queue names.
     */
    public Set<String> getQueueNames() {
        return Collections.unmodifiableSet(queues.keySet());
    }

    /**
     * Get queue order (by index).
     */
    public List<String> getQueueOrder() {
        return Collections.unmodifiableList(queueOrder);
    }
}
