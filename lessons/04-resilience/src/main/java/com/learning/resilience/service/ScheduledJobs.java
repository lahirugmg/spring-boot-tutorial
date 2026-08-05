package com.learning.resilience.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * INTERVIEW: "fixedRate vs fixedDelay vs cron?"
 *
 *   fixedDelay - waits N ms AFTER the previous run FINISHES. Runs never overlap, and the
 *                effective period is executionTime + delay. Safe default.
 *   fixedRate  - starts every N ms REGARDLESS of how long the previous run took. If a run
 *                exceeds the period, the next is queued and fires immediately after —
 *                they pile up. Use only when execution is reliably faster than the period.
 *   cron       - calendar-based ("0 0 3 * * *" = 03:00 daily). Note SPRING's cron has SIX
 *                fields (seconds first), unlike Unix cron's five. Always set the zone
 *                explicitly or you inherit the server's, which differs between laptop and
 *                production.
 *
 * THE BIG DEFAULT TO KNOW: the scheduler is a SINGLE-THREADED ThreadPoolTaskScheduler
 * (pool size 1). One slow job therefore delays EVERY other scheduled job in the app. Fix
 * it with spring.task.scheduling.pool.size (set in application.yml).
 *
 * THE BIG DISTRIBUTED PROBLEM: @Scheduled fires on EVERY instance. Three pods means the
 * nightly billing job runs three times. Solutions: ShedLock (a lock row in a shared
 * store), Quartz in clustered mode, a leader-election sidecar, or moving the trigger out
 * of the app entirely (a Kubernetes CronJob calling an endpoint).
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final AtomicInteger fixedDelayRuns = new AtomicInteger();
    private final AtomicInteger fixedRateRuns = new AtomicInteger();
    private final AtomicInteger cronRuns = new AtomicInteger();
    private volatile Instant lastRun;

    /** Waits 10s after the previous run ENDS. Cannot overlap with itself. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    void reportCacheStats() {
        fixedDelayRuns.incrementAndGet();
        lastRun = Instant.now();
        log.debug("[fixedDelay] heartbeat #{} on {}", fixedDelayRuns.get(),
                Thread.currentThread().getName());
    }

    /**
     * Starts every 15s by the clock. Because the pool has more than one thread (see
     * application.yml), a slow run here does not block reportCacheStats above.
     */
    @Scheduled(fixedRate = 15_000, initialDelay = 7_000)
    void refreshMetrics() {
        fixedRateRuns.incrementAndGet();
        log.debug("[fixedRate] tick #{} on {}", fixedRateRuns.get(),
                Thread.currentThread().getName());
    }

    /**
     * Six fields: second minute hour day-of-month month day-of-week.
     * This one fires at second 0 of every minute. The zone is explicit on purpose.
     */
    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    void minuteRollup() {
        cronRuns.incrementAndGet();
        log.debug("[cron] minute rollup #{} at {}", cronRuns.get(), Instant.now());
    }

    public java.util.Map<String, Object> stats() {
        return java.util.Map.of(
                "fixedDelayRuns", fixedDelayRuns.get(),
                "fixedRateRuns", fixedRateRuns.get(),
                "cronRuns", cronRuns.get(),
                "lastRun", String.valueOf(lastRun),
                "note", "in a multi-instance deployment every one of these fires on EVERY pod — "
                        + "use ShedLock or a clustered scheduler");
    }
}
