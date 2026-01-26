package com.pool.scheduler;

import com.pool.config.PoolConfig;
import com.pool.core.PrioritizedPayload;
import com.pool.core.TaskContext;
import com.pool.policy.DefaultPolicyEngine;
import com.pool.policy.EvaluationResult;
import com.pool.policy.PolicyEngine;
import com.pool.strategy.PriorityStrategy;
import com.pool.strategy.PriorityStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory priority scheduler for external consumers.
 * Evaluates policy and orders payloads without executing tasks.
 */
public class DefaultPriorityScheduler<T> implements PriorityScheduler<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultPriorityScheduler.class);

    private final PolicyEngine policyEngine;
    private final PriorityStrategy priorityStrategy;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public DefaultPriorityScheduler(PoolConfig config) {
        this(config, new DefaultPolicyEngine(config));
    }

    public DefaultPriorityScheduler(PoolConfig config, PolicyEngine policyEngine) {
        int capacity = config.scheduler() != null
                ? config.scheduler().queueCapacity()
                : com.pool.config.SchedulerConfig.defaults().queueCapacity();
        PriorityStrategy strategy = PriorityStrategyFactory.create(
                config.priorityStrategy(),
                capacity
        );
        this.policyEngine = policyEngine;
        this.priorityStrategy = strategy;
        log.info("DefaultPriorityScheduler initialized (strategy={})", strategy.getName());
    }

    public DefaultPriorityScheduler(PolicyEngine policyEngine, PriorityStrategy priorityStrategy) {
        this.policyEngine = policyEngine;
        this.priorityStrategy = priorityStrategy;
        log.info("DefaultPriorityScheduler initialized (strategy={})", priorityStrategy.getName());
    }

    @Override
    public boolean submit(TaskContext context, T payload) {
        if (context == null) {
            throw new NullPointerException("Context cannot be null");
        }
        if (shutdown.get()) {
            log.warn("Scheduler is shutdown, rejecting task: {}", context.getTaskId());
            return false;
        }

        EvaluationResult result = policyEngine.evaluate(context);
        PrioritizedPayload<T> item = new PrioritizedPayload<>(
                payload,
                context.getTaskId(),
                result.getPriorityKey()
        );
        return priorityStrategy.enqueue(item);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNext() throws InterruptedException {
        PrioritizedPayload<?> item = priorityStrategy.takeNext();
        PrioritizedPayload<T> typed = (PrioritizedPayload<T>) item;
        return typed.getPayload();
    }

    @Override
    public Optional<T> getNext(long timeout, TimeUnit unit) throws InterruptedException {
        Optional<PrioritizedPayload<?>> item = priorityStrategy.pollNext(timeout, unit);
        if (item.isEmpty()) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        PrioritizedPayload<T> typed = (PrioritizedPayload<T>) item.get();
        return Optional.ofNullable(typed.getPayload());
    }

    @Override
    public int size() {
        return priorityStrategy.getQueueSize();
    }

    @Override
    public int remainingCapacity() {
        return priorityStrategy.getRemainingCapacity();
    }

    @Override
    public void shutdown() {
        shutdown.set(true);
        priorityStrategy.shutdown();
        log.info("DefaultPriorityScheduler shutdown, {} items remaining", priorityStrategy.getQueueSize());
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }
}
