package com.learning.resilience.web;

import com.learning.resilience.service.AsyncService;
import com.learning.resilience.service.PricingClient;
import com.learning.resilience.service.ProductService;
import com.learning.resilience.service.ScheduledJobs;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final ProductService productService;
    private final AsyncService asyncService;
    private final PricingClient pricingClient;
    private final ScheduledJobs scheduledJobs;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public DemoController(ProductService productService, AsyncService asyncService,
                          PricingClient pricingClient, ScheduledJobs scheduledJobs,
                          CircuitBreakerRegistry circuitBreakerRegistry) {
        this.productService = productService;
        this.asyncService = asyncService;
        this.pricingClient = pricingClient;
        this.scheduledJobs = scheduledJobs;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    // =================================================================================
    // CACHING
    // =================================================================================

    /**
     * Times a cold call vs a warm one. The first pays the 300ms simulated lookup; the
     * second is served from Redis and should be an order of magnitude faster.
     */
    @GetMapping("/cache-timing/{id}")
    public Map<String, Object> cacheTiming(@PathVariable long id) {
        productService.evictOne(id);
        productService.resetCounter();

        long t1 = time(() -> productService.findById(id));
        long t2 = time(() -> productService.findById(id));
        long t3 = time(() -> productService.findById(id));

        var result = new LinkedHashMap<String, Object>();
        result.put("call1_coldMs", t1);
        result.put("call2_warmMs", t2);
        result.put("call3_warmMs", t3);
        result.put("actualDatabaseHits", productService.databaseHits());
        result.put("verdict", productService.databaseHits() == 1
                ? "cache working: 3 calls, 1 real lookup"
                : "cache NOT working — check Redis connectivity");
        return result;
    }

    /**
     * The self-invocation trap, applied to @Cacheable. Both loops make 3 calls; only the
     * proxied one is cached.
     */
    @GetMapping("/cache-self-invocation/{id}")
    public Map<String, Object> cacheSelfInvocation(@PathVariable long id) {
        var result = new LinkedHashMap<String, Object>();

        productService.evictAll();
        productService.resetCounter();
        IntStream.range(0, 3).forEach(i -> productService.findById(id));
        result.put("viaProxy_databaseHits", productService.databaseHits());

        productService.evictAll();
        productService.resetCounter();
        IntStream.range(0, 3).forEach(i -> productService.findByIdBypassingCache(id));
        result.put("viaSelfInvocation_databaseHits", productService.databaseHits());

        result.put("verdict", "`this.findById()` skips the CacheInterceptor entirely — "
                + "3 calls, 3 real lookups");
        return result;
    }

    @PostMapping("/cache/products/{id}/price")
    public Map<String, Object> updatePrice(@PathVariable long id, @RequestParam BigDecimal price) {
        productService.resetCounter();
        var updated = productService.updatePrice(id, price);
        // Reads straight from the cache — @CachePut refreshed it, so no lookup occurs.
        var readBack = productService.findById(id);
        return Map.of(
                "updated", updated,
                "readBack", readBack,
                "databaseHitsAfterUpdate", productService.databaseHits(),
                "verdict", "@CachePut ran the method AND refreshed the cache, so the read-back was free");
    }

    @PostMapping("/cache/evict-all")
    public Map<String, String> evictAll() {
        productService.evictAll();
        return Map.of("status", "evicted");
    }

    // =================================================================================
    // ASYNC
    // =================================================================================

    /**
     * Three calls of 400 + 500 + 600ms. Sequentially that is ~1500ms; run concurrently and
     * joined it should take about as long as the slowest one (~600ms).
     */
    @GetMapping("/async-parallel")
    public Map<String, Object> asyncParallel(@RequestParam(defaultValue = "u1") String userId) {
        long start = System.currentTimeMillis();

        CompletableFuture<String> profile = asyncService.fetchProfile(userId);
        CompletableFuture<String> orders = asyncService.fetchOrders(userId);
        CompletableFuture<String> recs = asyncService.fetchRecommendations(userId);

        // allOf then join: the total wait is the SLOWEST call, not the sum.
        CompletableFuture.allOf(profile, orders, recs).join();
        long elapsed = System.currentTimeMillis() - start;

        return Map.of(
                "results", List.of(profile.join(), orders.join(), recs.join()),
                "elapsedMs", elapsed,
                "sequentialWouldBeMs", 400 + 500 + 600,
                "verdict", elapsed < 1200 ? "ran concurrently" : "ran sequentially — check @EnableAsync");
    }

    @GetMapping("/async-self-invocation")
    public Map<String, Object> asyncSelfInvocation(@RequestParam(defaultValue = "u1") String userId) {
        long start = System.currentTimeMillis();
        var future = asyncService.selfInvocationIsSynchronous(userId);
        long beforeJoin = System.currentTimeMillis() - start;

        return Map.of(
                "msElapsedBeforeJoin", beforeJoin,
                "result", future.join(),
                "callerThread", Thread.currentThread().getName(),
                "verdict", beforeJoin > 300
                        ? "BLOCKED before join -> ran synchronously; the proxy was bypassed"
                        : "returned immediately -> genuinely asynchronous");
    }

    /**
     * A void @Async that throws. The caller sees nothing; only the
     * AsyncUncaughtExceptionHandler logs it. Watch the server log.
     */
    @PostMapping("/async-fire-and-forget")
    public Map<String, Object> fireAndForget(@RequestParam(defaultValue = "true") boolean fail) {
        asyncService.fireAndForget(fail);
        return Map.of(
                "returned", "immediately",
                "note", fail
                        ? "an exception was thrown on the async thread — check the server log; "
                          + "the caller CANNOT see it"
                        : "task completed normally",
                "completions", asyncService.voidTaskCompletions());
    }

    // =================================================================================
    // RESILIENCE
    // =================================================================================

    /**
     * Drives the circuit breaker through its states. Point the upstream at 100% failure
     * first, then call this and watch the state go CLOSED -> OPEN.
     *
     *   curl -X POST 'localhost:8084/api/upstream/mode?failureRate=100'
     *   curl 'localhost:8084/api/demo/circuit-breaker?calls=20'
     */
    @GetMapping("/circuit-breaker")
    public Map<String, Object> circuitBreaker(@RequestParam(defaultValue = "12") int calls) {
        pricingClient.resetCounters();
        var breaker = circuitBreakerRegistry.circuitBreaker("pricingApi");
        breaker.reset();

        var timeline = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= calls; i++) {
            var stateBefore = breaker.getState().name();
            var quote = pricingClient.fetchPrice(1L);
            timeline.add(Map.of(
                    "call", i,
                    "stateBefore", stateBefore,
                    "stateAfter", breaker.getState().name(),
                    "source", quote.source(),
                    "degraded", quote.degraded()));
        }

        var metrics = breaker.getMetrics();
        return Map.of(
                "timeline", timeline,
                "finalState", breaker.getState().name(),
                "upstreamAttempts", pricingClient.attempts(),
                "fallbacksServed", pricingClient.fallbacks(),
                "failureRate", metrics.getFailureRate() + "%",
                "bufferedCalls", metrics.getNumberOfBufferedCalls(),
                "readingGuide", "once OPEN, note that upstreamAttempts stops rising — "
                        + "calls fail fast without touching the upstream at all");
    }

    /** Retry in isolation: one logical call, several upstream attempts. */
    @GetMapping("/retry")
    public Map<String, Object> retry() {
        pricingClient.resetCounters();
        circuitBreakerRegistry.circuitBreaker("pricingApi").reset();
        var quote = pricingClient.fetchPrice(1L);
        return Map.of(
                "quote", quote,
                "upstreamAttempts", pricingClient.attempts(),
                "verdict", pricingClient.attempts() > 1
                        ? "retried before giving up"
                        : "succeeded first time");
    }

    @GetMapping("/rate-limiter")
    public Map<String, Object> rateLimiter(@RequestParam(defaultValue = "10") int calls) {
        var outcomes = new ArrayList<String>();
        for (int i = 0; i < calls; i++) {
            outcomes.add(pricingClient.fetchPriceRateLimited(1L).source());
        }
        long limited = outcomes.stream().filter("rate-limited"::equals).count();
        return Map.of(
                "outcomes", outcomes,
                "rateLimitedCount", limited,
                "verdict", "the limiter permits N calls per refresh period; the rest hit the fallback");
    }

    // =================================================================================
    // SCHEDULING
    // =================================================================================

    @GetMapping("/scheduled")
    public Map<String, Object> scheduled() {
        return scheduledJobs.stats();
    }

    private static long time(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
