package com.learning.resilience.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * INTERVIEW: "What's wrong with plain @Async out of the box?"
 *
 * By default Spring uses SimpleAsyncTaskExecutor, which creates a NEW THREAD PER TASK and
 * never pools. Under load that is unbounded thread creation — a straight path to
 * OutOfMemoryError. Always define your own executor.
 *
 * (Boot mitigates this if spring.threads.virtual.enabled=true on Java 21+, where the
 * per-task thread is a cheap virtual thread. See application.yml.)
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * SIZING A THREAD POOL — be ready to justify these numbers.
     *
     * ThreadPoolTaskExecutor grows in a counter-intuitive order:
     *   1. create threads up to corePoolSize
     *   2. then QUEUE tasks until the queue is full
     *   3. only THEN create threads up to maxPoolSize
     *   4. queue full and maxPoolSize reached -> RejectedExecutionHandler
     *
     * So with an unbounded queue (the default, Integer.MAX_VALUE) maxPoolSize is NEVER
     * reached — the queue absorbs everything, latency climbs silently, and you run out of
     * memory instead of shedding load. A BOUNDED queue is what makes maxPoolSize mean
     * anything.
     *
     * Rough sizing: CPU-bound -> ~cores. IO-bound -> cores * (1 + wait/service time).
     */
    @Override
    @Bean("applicationTaskExecutor")
    public Executor getAsyncExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);          // bounded on purpose
        executor.setThreadNamePrefix("async-");
        executor.setKeepAliveSeconds(60);

        // CallerRunsPolicy applies BACKPRESSURE: when saturated, the submitting thread
        // runs the task itself, which slows the producer instead of dropping work.
        // The alternative, AbortPolicy, throws RejectedExecutionException.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Let in-flight tasks finish on shutdown instead of losing them.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.setTaskDecorator(contextPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * INTERVIEW: "You call an @Async method — is the SecurityContext still there?"
     *
     * NO. SecurityContextHolder and the SLF4J MDC are both ThreadLocals, and the async task
     * runs on a DIFFERENT, POOLED thread. Two consequences:
     *   - the authentication is missing, so a @PreAuthorize inside the async method fails
     *     (or worse, a null principal produces a confusing NPE)
     *   - correlation ids vanish from the logs at exactly the moment you need them
     *
     * Worse still, because the thread is POOLED AND REUSED, a value left behind leaks into
     * an unrelated later task. That is why the finally block clears unconditionally.
     *
     * A TaskDecorator captures the submitting thread's context and restores it inside the
     * worker. This one propagates the MDC; module 04 has no Spring Security on the
     * classpath, so the security half is left out deliberately — in a secured service you
     * would either add SecurityContextHolder.getContext() to this same decorator, or wrap
     * the executor in Spring Security's DelegatingSecurityContextExecutor, which does
     * exactly that.
     *
     * On Java 21 with spring.threads.virtual.enabled=true, ScopedValue-based propagation
     * (and Micrometer's ContextSnapshot) are the forward-looking answer to this problem.
     */
    private TaskDecorator contextPropagatingDecorator() {
        return runnable -> {
            Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (mdc != null) {
                        org.slf4j.MDC.setContextMap(mdc);
                    }
                    runnable.run();
                } finally {
                    // MUST clear: pooled threads outlive the task that set this.
                    org.slf4j.MDC.clear();
                }
            };
        };
    }

    /**
     * INTERVIEW: "What happens to an exception thrown inside an @Async method?"
     *
     *   returns CompletableFuture<T> -> the exception completes the future exceptionally;
     *                                   the caller sees it on get()/join().
     *   returns void                 -> IT IS SWALLOWED. Nothing logs it by default.
     *
     * That second case is how async failures go unnoticed for months. AsyncConfigurer lets
     * you install a handler for exactly that case.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.error("Uncaught exception from void @Async method {}.{}() — this would be "
                                + "SILENT without an AsyncUncaughtExceptionHandler",
                        method.getDeclaringClass().getSimpleName(), method.getName(), throwable);
    }
}
