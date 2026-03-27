package com.pool.aspect;

import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.annotation.Pooled;
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;
import com.pool.core.TpsContext;
import com.pool.exception.TpsExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final TpsPoolExecutor tpsPoolExecutor;
    private final ObjectMapper objectMapper;

    public RequestInterceptorAspect(TpsPoolExecutor tpsPoolExecutor,
                                     ObjectMapper objectMapper) {
        this.tpsPoolExecutor = tpsPoolExecutor;
        this.objectMapper = objectMapper;

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

        // Request vars: parsed from the contextType argument
        String jsonPayload = null;
        if (pooled.contextType() != Void.class) {
            for (Object arg : pjp.getArgs()) {
                if (arg != null && pooled.contextType().isInstance(arg)) {
                    try {
                        jsonPayload = objectMapper.writeValueAsString(arg);
                    } catch (Exception e) {
                        log.warn("Failed to serialize request arg of type {}: {}",
                                pooled.contextType().getSimpleName(), e.getMessage());
                    }
                    break;
                }
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
