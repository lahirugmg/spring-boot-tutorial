package com.learning.resilience;

import com.learning.resilience.service.PricingClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Uses DEFINED_PORT so the app's own flaky-upstream controller is reachable at the URL the
 * RestClient was configured with. app.upstream.base-url must match server.port, and a
 * RANDOM_PORT is not known early enough to wire into the RestClient bean.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18084",
                "app.upstream.base-url=http://localhost:18084"
        })
@Import(TestcontainersConfiguration.class)
class ResiliencePatternsIT {

    private static final String BACKEND = "pricingApi";

    @Autowired
    private PricingClient pricingClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RestClient restClient;

    private CircuitBreaker breaker() {
        return circuitBreakerRegistry.circuitBreaker(BACKEND);
    }

    private void setUpstream(int failureRate, long latencyMs) {
        restClient.post()
                .uri("/api/upstream/mode?failureRate={f}&latencyMs={l}", failureRate, latencyMs)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toBodilessEntity();
    }

    @BeforeEach
    void reset() {
        breaker().reset();
        pricingClient.resetCounters();
        setUpstream(0, 0);
    }

    @Test
    @DisplayName("a healthy upstream needs no retry and no fallback")
    void healthyUpstreamSucceedsFirstTime() {
        var quote = pricingClient.fetchPrice(1L);

        assertThat(quote.degraded()).isFalse();
        assertThat(quote.source()).isEqualTo("upstream");
        assertThat(pricingClient.attempts()).isEqualTo(1);
        assertThat(pricingClient.fallbacks()).isZero();
    }

    /**
     * THE ORDERING TEST. With the default aspect order (Retry outermost, breaker inside
     * with the fallback) this asserts 1 and fails — because the inner fallback converts
     * the failure into a success and the outer Retry never fires.
     *
     * With circuitBreakerAspectOrder=1 / retryAspectOrder=2 the breaker wraps the retry,
     * so one logical call makes maxAttempts=3 upstream attempts.
     */
    @Test
    @DisplayName("retry makes maxAttempts upstream calls for ONE logical call")
    void retryIsActuallyApplied() {
        setUpstream(100, 0);

        var quote = pricingClient.fetchPrice(1L);

        assertThat(pricingClient.attempts())
                .as("1 initial + 2 retries; a value of 1 means the fallback swallowed the "
                        + "exception before Retry could see it")
                .isEqualTo(3);
        assertThat(quote.degraded()).isTrue();
        assertThat(quote.source()).startsWith("fallback:");
    }

    @Test
    @DisplayName("the breaker opens after minimumNumberOfCalls failures, then fails fast")
    void circuitOpensAndFailsFast() {
        setUpstream(100, 0);
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // minimumNumberOfCalls = 5, failureRateThreshold = 50%
        IntStream.rangeClosed(1, 5).forEach(i -> pricingClient.fetchPrice(1L));
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int attemptsWhenOpened = pricingClient.attempts();

        // Once OPEN, calls are rejected without touching the upstream at all.
        IntStream.rangeClosed(1, 5).forEach(i -> pricingClient.fetchPrice(1L));

        assertThat(pricingClient.attempts())
                .as("no further upstream traffic while OPEN — that is the whole point")
                .isEqualTo(attemptsWhenOpened);
        assertThat(pricingClient.fallbacks()).isEqualTo(10);
    }

    @Test
    @DisplayName("OPEN -> HALF_OPEN -> CLOSED once the upstream recovers")
    void circuitRecoversThroughHalfOpen() {
        setUpstream(100, 0);
        IntStream.rangeClosed(1, 5).forEach(i -> pricingClient.fetchPrice(1L));
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        setUpstream(0, 0);

        // waitDurationInOpenState = 5s, automaticTransitionFromOpenToHalfOpenEnabled = true
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(breaker().getState())
                        .isEqualTo(CircuitBreaker.State.HALF_OPEN));

        // permittedNumberOfCallsInHalfOpenState = 3 successful trials close it again.
        IntStream.rangeClosed(1, 3).forEach(i -> pricingClient.fetchPrice(1L));

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("the fallback degrades gracefully instead of propagating the failure")
    void fallbackDegradesGracefully() {
        setUpstream(100, 0);

        var quote = pricingClient.fetchPrice(7L);

        // The caller gets an answer, flagged as degraded, rather than an exception.
        assertThat(quote.productId()).isEqualTo(7L);
        assertThat(quote.degraded()).isTrue();
        assertThat(pricingClient.fallbacks()).isEqualTo(1);
    }

    @Test
    @DisplayName("the rate limiter permits limitForPeriod calls then rejects the rest")
    void rateLimiterCapsThroughput() {
        // limitForPeriod = 5 per 10s, timeoutDuration = 0 (fail fast, do not queue)
        var outcomes = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> pricingClient.fetchPriceRateLimited(1L).source())
                .toList();

        assertThat(outcomes.stream().filter(s -> s.equals("rate-limited")).count())
                .as("8 calls against a 5-permit window")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an unprotected call propagates the upstream failure straight to the caller")
    void unprotectedCallHasNoSafetyNet() {
        setUpstream(100, 0);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> pricingClient.fetchPriceUnprotected(1L))
                .isInstanceOf(org.springframework.web.client.RestClientException.class);
    }
}
