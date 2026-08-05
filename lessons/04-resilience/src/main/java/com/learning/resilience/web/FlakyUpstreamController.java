package com.learning.resilience.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for an unreliable third-party API so the resilience patterns have something
 * real to fail against. Its behaviour is controlled at runtime:
 *
 *   POST /api/upstream/mode?failureRate=100&latencyMs=0   -> always 500
 *   POST /api/upstream/mode?failureRate=0                 -> always healthy
 *   POST /api/upstream/mode?failureRate=0&latencyMs=3000  -> healthy but slow
 *
 * Deterministic rather than random (every Nth call fails) so demos and tests reproduce.
 */
@RestController
@RequestMapping("/api/upstream")
public class FlakyUpstreamController {

    private static final Logger log = LoggerFactory.getLogger(FlakyUpstreamController.class);

    private volatile int failureRate = 0;      // percent
    private volatile long latencyMs = 0;
    private final AtomicInteger callCount = new AtomicInteger();

    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestParam(defaultValue = "0") int failureRate,
                                       @RequestParam(defaultValue = "0") long latencyMs) {
        this.failureRate = Math.clamp(failureRate, 0, 100);
        this.latencyMs = Math.max(0, latencyMs);
        this.callCount.set(0);
        log.info("Upstream mode set: failureRate={}%, latency={}ms", this.failureRate, this.latencyMs);
        return Map.of("failureRate", this.failureRate, "latencyMs", this.latencyMs);
    }

    @GetMapping("/price")
    public Map<String, Object> price(@RequestParam(defaultValue = "1") long productId) {
        int call = callCount.incrementAndGet();

        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Deterministic: with failureRate=40, calls 1-4 of every 10 fail.
        boolean shouldFail = failureRate > 0 && (call % 100) <= failureRate && (call % 100) != 0;
        if (failureRate >= 100) {
            shouldFail = true;
        }

        if (shouldFail) {
            log.warn("Upstream call #{} FAILING (rate={}%)", call, failureRate);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "upstream exploded");
        }

        log.info("Upstream call #{} OK", call);
        return Map.of("productId", productId, "price", 99.95, "source", "upstream", "call", call);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("failureRate", failureRate, "latencyMs", latencyMs, "callsReceived", callCount.get());
    }
}
