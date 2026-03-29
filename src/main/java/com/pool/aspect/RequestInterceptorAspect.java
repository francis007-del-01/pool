package com.pool.aspect;

import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.annotation.PoolContextBuilder;
import com.pool.annotation.Pooled;
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;
import com.pool.core.TpsContext;
import com.pool.exception.ConfigurationException;
import com.pool.exception.TpsExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring AOP aspect that intercepts methods on classes/methods annotated with {@link Pooled}.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Check TpsContext — if already admitted, pass through immediately</li>
 *   <li>Build TaskContext from MDC + parsed request arg + implicit variables</li>
 *   <li>Delegate admission, queuing, and execution to {@link TpsPoolExecutor}</li>
 * </ol>
 *
 * <p>All TPS gating, PolicyEngine evaluation, and queue management live in
 * {@link TpsPoolExecutor} — the aspect only builds context and delegates.
 */
@Aspect
@Component
public class RequestInterceptorAspect {

    private static final Logger log = LoggerFactory.getLogger(RequestInterceptorAspect.class);
    private static final long DEFAULT_TIMEOUT_MS = 10000;

    private final TpsPoolExecutor tpsPoolExecutor;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    public RequestInterceptorAspect(TpsPoolExecutor tpsPoolExecutor,
                                     ObjectMapper objectMapper,
                                     ApplicationContext applicationContext) {
        this.tpsPoolExecutor = tpsPoolExecutor;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;

        log.info("RequestInterceptorAspect initialized");
    }

    @Around("@within(pooled)")
    public Object interceptClass(ProceedingJoinPoint pjp, Pooled pooled) throws Throwable {
        return doIntercept(pjp, pooled);
    }

    @Around("@annotation(pooled)")
    public Object interceptMethod(ProceedingJoinPoint pjp, Pooled pooled) throws Throwable {
        return doIntercept(pjp, pooled);
    }

    private Object doIntercept(ProceedingJoinPoint pjp, Pooled pooled) throws Throwable {
        if (TpsContext.isProcessed()) {
            return pjp.proceed();
        }

        TaskContext taskContext = buildTaskContext(pjp, pooled);

        long timeout = pooled.timeoutMs() > 0 ? pooled.timeoutMs() : DEFAULT_TIMEOUT_MS;

        Future<Object> future = tpsPoolExecutor.submit(taskContext, () -> {
            TpsContext.markProcessed();
            try {
                return pjp.proceed();
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            } finally {
                TpsContext.clear();
            }
        });

        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TpsExceededException(
                    "TPS admission timed out after " + timeout + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw cause;
        }
    }

    private TaskContext buildTaskContext(ProceedingJoinPoint pjp, Pooled pooled) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();

        String jsonPayload = null;
        if (pooled.contextType() != Void.class) {
            Object bean;
            try {
                bean = applicationContext.getBean(pooled.contextType());
            } catch (Exception e) {
                throw new ConfigurationException(
                        "No Spring bean found for contextType " + pooled.contextType().getSimpleName()
                        + " — declare it as a @Component");
            }
            if (!(bean instanceof PoolContextBuilder builder)) {
                throw new ConfigurationException(
                        pooled.contextType().getSimpleName() + " must implement PoolContextBuilder");
            }
            try {
                Object context = builder.build(pjp.getArgs());
                jsonPayload = context != null ? objectMapper.writeValueAsString(context) : null;
            } catch (ConfigurationException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to build/serialize context via {}: {}",
                        pooled.contextType().getSimpleName(), e.getMessage());
            }
        }

        // Context vars: MDC + implicit _class / _method
        Map<String, String> contextVars = new HashMap<>();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        if (mdcContext != null) {
            contextVars.putAll(mdcContext);
        }
        contextVars.put("_class", pjp.getTarget().getClass().getSimpleName());
        contextVars.put("_method", sig.getName());

        return TaskContextFactory.create(jsonPayload, contextVars);
    }
}
