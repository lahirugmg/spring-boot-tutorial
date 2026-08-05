package com.learning.resilience.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AsyncService {

    private static final Logger log = LoggerFactory.getLogger(AsyncService.class);

    private final AtomicInteger voidTaskCompletions = new AtomicInteger();

    /**
     * The GOOD shape: return CompletableFuture so the caller can compose, time out, and
     * — crucially — actually observe failures.
     *
     * Note the return type is CompletableFuture, not the ancient
     * org.springframework.scheduling.annotation.AsyncResult (deprecated in Boot 3).
     */
    @Async
    public CompletableFuture<String> fetchProfile(String userId) {
        log.info("fetchProfile({}) on thread {}", userId, Thread.currentThread().getName());
        sleep(400);
        return CompletableFuture.completedFuture("profile:" + userId);
    }

    @Async
    public CompletableFuture<String> fetchOrders(String userId) {
        log.info("fetchOrders({}) on thread {}", userId, Thread.currentThread().getName());
        sleep(500);
        return CompletableFuture.completedFuture("orders:" + userId);
    }

    @Async
    public CompletableFuture<String> fetchRecommendations(String userId) {
        log.info("fetchRecommendations({}) on thread {}", userId, Thread.currentThread().getName());
        sleep(600);
        return CompletableFuture.completedFuture("recs:" + userId);
    }

    /**
     * A failure surfaced through the future — the caller CAN see this one.
     */
    @Async
    public CompletableFuture<String> failingFuture() {
        return CompletableFuture.failedFuture(new IllegalStateException("upstream said no"));
    }

    /**
     * The DANGEROUS shape. A void @Async method's exception never reaches the caller;
     * without the AsyncUncaughtExceptionHandler in AsyncConfig it would vanish entirely.
     * Watch the log after calling /api/async/fire-and-forget?fail=true.
     */
    @Async
    public void fireAndForget(boolean fail) {
        log.info("fireAndForget on thread {}", Thread.currentThread().getName());
        sleep(200);
        if (fail) {
            throw new IllegalStateException("void @Async failure — invisible to the caller");
        }
        voidTaskCompletions.incrementAndGet();
    }

    /**
     * Self-invocation, one more time. No @Async on this method, and `this.fetchProfile(...)`
     * bypasses the proxy — so it runs SYNCHRONOUSLY on the caller's thread despite the
     * annotation. The endpoint reports the thread name so you can see it.
     */
    public CompletableFuture<String> selfInvocationIsSynchronous(String userId) {
        return this.fetchProfile(userId);
    }

    public int voidTaskCompletions() {
        return voidTaskCompletions.get();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
