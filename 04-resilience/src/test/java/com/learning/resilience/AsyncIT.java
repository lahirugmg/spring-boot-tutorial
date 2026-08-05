package com.learning.resilience;

import com.learning.resilience.service.AsyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AsyncIT {

    @Autowired
    private AsyncService asyncService;

    @Test
    @DisplayName("@Async methods run on the configured pool, not the caller thread")
    void runsOnTheAsyncPool() {
        String callerThread = Thread.currentThread().getName();

        // The result is produced on a pool thread; the future returns immediately here.
        CompletableFuture<String> future = asyncService.fetchProfile("u1");

        assertThat(future.join()).isEqualTo("profile:u1");
        assertThat(callerThread).doesNotStartWith("async-");
    }

    @Test
    @DisplayName("three async calls overlap instead of summing")
    void callsRunConcurrently() {
        long start = System.currentTimeMillis();

        var a = asyncService.fetchProfile("u1");      // 400ms
        var b = asyncService.fetchOrders("u1");       // 500ms
        var c = asyncService.fetchRecommendations("u1"); // 600ms
        CompletableFuture.allOf(a, b, c).join();

        long elapsed = System.currentTimeMillis() - start;

        // Sequential would be ~1500ms. Allow generous headroom for a loaded CI machine
        // while still failing loudly if the calls serialised.
        assertThat(elapsed)
                .as("elapsed %dms should be near the slowest call (600ms), not the sum (1500ms)", elapsed)
                .isLessThan(1200);
        assertThat(a.join()).isEqualTo("profile:u1");
    }

    @Test
    @DisplayName("an exception in a CompletableFuture-returning @Async reaches the caller")
    void futureExceptionIsVisible() {
        var future = asyncService.failingFuture();

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upstream said no");
    }

    /**
     * The dangerous shape: a void @Async swallows its exception. The caller gets nothing —
     * no throw, no failed future. Only the AsyncUncaughtExceptionHandler sees it.
     *
     * The assertion is that the CALLER is unaffected, which is exactly the hazard.
     */
    @Test
    @DisplayName("a void @Async failure is invisible to the caller")
    void voidAsyncExceptionIsSwallowed() {
        // Does not throw, even though the task will fail on the pool thread.
        asyncService.fireAndForget(true);

        int before = asyncService.voidTaskCompletions();
        // Give the pool time to run and fail the task.
        await().atMost(2, TimeUnit.SECONDS)
                .pollDelay(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(asyncService.voidTaskCompletions())
                        .as("the failing task never increments the counter")
                        .isEqualTo(before));
    }

    @Test
    @DisplayName("a successful void @Async does complete")
    void voidAsyncSucceeds() {
        int before = asyncService.voidTaskCompletions();

        asyncService.fireAndForget(false);

        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(asyncService.voidTaskCompletions()).isEqualTo(before + 1));
    }

    @Test
    @DisplayName("self-invocation makes @Async run synchronously on the caller thread")
    void selfInvocationIsSynchronous() {
        long start = System.currentTimeMillis();
        var future = asyncService.selfInvocationIsSynchronous("u1");
        long elapsedBeforeJoin = System.currentTimeMillis() - start;

        // A genuinely async call returns in ~0ms; this one blocks for the full 400ms
        // because the proxy was bypassed and the method ran inline.
        assertThat(elapsedBeforeJoin)
                .as("blocked for %dms before join -> it ran synchronously", elapsedBeforeJoin)
                .isGreaterThanOrEqualTo(350);
        assertThat(future.join()).isEqualTo("profile:u1");
    }
}
