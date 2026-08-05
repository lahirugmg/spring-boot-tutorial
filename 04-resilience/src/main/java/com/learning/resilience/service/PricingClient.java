package com.learning.resilience.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * INTERVIEW: "RestTemplate vs WebClient vs RestClient?"
 *
 *   RestTemplate - the original, blocking, synchronous client. In maintenance mode since
 *                  Spring 5: not deprecated, but no new features.
 *   WebClient    - reactive and non-blocking, from spring-webflux. Can be used
 *                  synchronously with .block(), but pulling in the whole reactive stack
 *                  just for that is heavy-handed.
 *   RestClient   - Spring 6.1+. A modern FLUENT API with WebClient's ergonomics and
 *                  RestTemplate's blocking model, in spring-web. This is the default
 *                  choice for a synchronous client in a new Boot 3.2+ app.
 *
 * Also worth naming: @HttpExchange declarative interfaces backed by RestClient/WebClient —
 * Spring's answer to Feign, with no extra dependency.
 *
 * A detail worth mentioning: ALWAYS set connect and read timeouts. RestTemplate's default
 * is INFINITE, which means one hung upstream can exhaust your entire request thread pool.
 * That is configured on the builder in RestClientConfig.
 */
@Service
public class PricingClient {

    private static final Logger log = LoggerFactory.getLogger(PricingClient.class);
    private static final String BACKEND = "pricingApi";

    private final RestClient restClient;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger fallbacks = new AtomicInteger();

    public PricingClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * RETRY + CIRCUIT BREAKER, and the ORDER GENUINELY MATTERS.
     *
     * Resilience4j's DEFAULT aspect order is, from outermost inwards:
     *
     *     Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( call ) ) ) ) )
     *
     * so by default Retry is the OUTERMOST decorator and the breaker sits inside it.
     * That default causes two problems, and this module hit BOTH:
     *
     *  PROBLEM 1 — the breaker trips too early. Every retry attempt passes through the
     *  breaker separately, so one logical call with 3 attempts records THREE failures.
     *  A window sized for 10 calls fills after ~3 user requests, and the circuit opens far
     *  sooner than the configuration implies.
     *
     *  PROBLEM 2 — retry silently stops working. If the INNER annotation declares a
     *  fallbackMethod, the CircuitBreaker aspect catches the exception and RETURNS THE
     *  FALLBACK VALUE. To the outer Retry that is a perfectly successful call, so it never
     *  retries. The symptom is subtle: everything "works", the fallback is served, and the
     *  upstream attempt count stays stubbornly at 1 per request.
     *
     * THE FIX, applied in application.yml, is Resilience4j's own recommendation for
     * Spring Boot 3 — invert the order so the breaker wraps the whole retry sequence
     * (lower order value = higher precedence = further out):
     *
     *     resilience4j.circuitbreaker.circuitBreakerAspectOrder: 1   # outermost
     *     resilience4j.retry.retryAspectOrder: 2                     # inside the breaker
     *
     * Now one logical call = up to 3 upstream attempts = exactly ONE outcome recorded by
     * the breaker, and the fallback belongs on the OUTERMOST annotation (@CircuitBreaker)
     * so it catches both a retry-exhausted failure and CallNotPermittedException.
     *
     * INTERVIEW: "What do the circuit breaker states mean?"
     *   CLOSED    - normal. Calls pass through; failures are counted in a sliding window.
     *   OPEN      - the failure threshold was crossed. Calls FAIL IMMEDIATELY without
     *               touching the upstream (CallNotPermittedException), giving it room to
     *               recover and freeing your threads.
     *   HALF_OPEN - after waitDurationInOpenState, a limited number of trial calls is let
     *               through. Enough succeed -> CLOSED; otherwise -> OPEN again.
     * Plus two manual states: DISABLED and FORCED_OPEN.
     *
     * The fallback method must have the SAME signature plus a trailing Throwable
     * parameter, or Resilience4j cannot bind it (a very common "fallback never fires" bug).
     */
    @CircuitBreaker(name = BACKEND, fallbackMethod = "priceFallback")
    @Retry(name = BACKEND)
    public PriceQuote fetchPrice(long productId) {
        attempts.incrementAndGet();
        log.info("Calling upstream for product {} (attempt #{})", productId, attempts.get());

        Map<?, ?> body = restClient.get()
                .uri("/api/upstream/price?productId={id}", productId)
                .retrieve()
                .body(Map.class);

        BigDecimal price = new BigDecimal(String.valueOf(body.get("price")));
        return new PriceQuote(productId, price, "upstream", false);
    }

    /**
     * The fallback. It must be resilient itself — never call another remote service here
     * without its own protection, or a failure cascade just moves one hop along.
     *
     * Returning stale-but-serviceable data ("graceful degradation") is usually far better
     * than a 500. Mark it clearly so callers know it is degraded.
     */
    @SuppressWarnings("unused")     // invoked reflectively by Resilience4j
    private PriceQuote priceFallback(long productId, Throwable throwable) {
        fallbacks.incrementAndGet();
        log.warn("FALLBACK for product {} after {}: {}", productId,
                throwable.getClass().getSimpleName(), throwable.getMessage());
        return new PriceQuote(productId, new BigDecimal("0.00"), "fallback:" +
                throwable.getClass().getSimpleName(), true);
    }

    /**
     * RATE LIMITER: caps calls per time window, protecting a quota-limited upstream (or
     * your own service from a noisy client). Distinct from a bulkhead, which caps
     * CONCURRENCY rather than RATE.
     */
    @RateLimiter(name = BACKEND, fallbackMethod = "rateLimitFallback")
    public PriceQuote fetchPriceRateLimited(long productId) {
        return fetchPrice(productId);
    }

    @SuppressWarnings("unused")
    private PriceQuote rateLimitFallback(long productId, Throwable throwable) {
        return new PriceQuote(productId, new BigDecimal("0.00"), "rate-limited", true);
    }

    /**
     * BULKHEAD: the name comes from a ship's watertight compartments — one flooded section
     * must not sink the vessel. It caps how many calls may run CONCURRENTLY, so a slow
     * upstream cannot consume every thread and take the whole service down with it.
     *
     * Two flavours: SEMAPHORE (default, cheap, caller's thread) and THREADPOOL (isolated
     * pool, Hystrix-style, allows real timeouts but costs context switches).
     */
    @Bulkhead(name = BACKEND, fallbackMethod = "bulkheadFallback")
    public PriceQuote fetchPriceBulkheaded(long productId) {
        return fetchPrice(productId);
    }

    @SuppressWarnings("unused")
    private PriceQuote bulkheadFallback(long productId, Throwable throwable) {
        return new PriceQuote(productId, new BigDecimal("0.00"), "bulkhead-rejected", true);
    }

    /** Calls the upstream with NO protection, for before/after comparison. */
    public PriceQuote fetchPriceUnprotected(long productId) {
        Map<?, ?> body = restClient.get()
                .uri("/api/upstream/price?productId={id}", productId)
                .retrieve()
                .body(Map.class);
        return new PriceQuote(productId, new BigDecimal(String.valueOf(body.get("price"))),
                "upstream-unprotected", false);
    }

    public int attempts() { return attempts.get(); }
    public int fallbacks() { return fallbacks.get(); }
    public void resetCounters() { attempts.set(0); fallbacks.set(0); }

    public record PriceQuote(long productId, BigDecimal price, String source, boolean degraded) {}
}
