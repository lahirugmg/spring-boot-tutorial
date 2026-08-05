# Module 04 — Caching & Resilience

Redis caching, `@Async`, `@Scheduled`, and the Resilience4j patterns. Needs Redis on Colima.

```bash
make cache       # redis on localhost:56379
make run-04      # http://localhost:8084
```

The app hosts its own **flaky upstream** at `/api/upstream/**`, so the resilience patterns
have something real to fail against — controllable at runtime:

```bash
curl -X POST 'localhost:8084/api/upstream/mode?failureRate=100'            # always fail
curl -X POST 'localhost:8084/api/upstream/mode?failureRate=0'              # healthy
curl -X POST 'localhost:8084/api/upstream/mode?failureRate=0&latencyMs=3000' # slow
```

---

## Caching

```bash
curl -s localhost:8084/api/demo/cache-timing/1 | jq
# { "call1_coldMs": 317, "call2_warmMs": 23, "call3_warmMs": 1, "actualDatabaseHits": 1 }

curl -s localhost:8084/api/demo/cache-self-invocation/2 | jq
# { "viaProxy_databaseHits": 1, "viaSelfInvocation_databaseHits": 3 }
```

Look at what actually lands in Redis:

```bash
docker exec sb-redis redis-cli KEYS '*'
docker exec sb-redis redis-cli GET 'products::product:3'
# {"@class":"com.learning.resilience.domain.Product","id":["java.lang.Long",3], ...}
docker exec sb-redis redis-cli TTL 'products::product:3'    # 1800
```

Readable JSON with a type id, and a real per-cache TTL — that is what the serializer
config in [CacheConfig.java](src/main/java/com/learning/resilience/config/CacheConfig.java)
buys you over the JDK-serialization default.

### The serialization bug worth knowing

`Product` is a **record**, and records are `final`. With the usual
`DefaultTyping.NON_FINAL`, Jackson writes **no** `@class` (runtime type is final) but
demands one when reading (declared type is `Object`):

```
InvalidTypeIdException: Could not resolve subtype of [simple type, class java.lang.Object]:
missing type id property '@class'
```

Writes succeed, reads fail, and only on a cache **hit** — so it presents as an intermittent
Redis fault. Fix: `DefaultTyping.EVERYTHING`, or a typed
`Jackson2JsonRedisSerializer<Product>` per cache.

---

## Async

```bash
curl -s localhost:8084/api/demo/async-parallel | jq
# { "elapsedMs": 609, "sequentialWouldBeMs": 1500, "verdict": "ran concurrently" }

curl -s localhost:8084/api/demo/async-self-invocation | jq
# { "msElapsedBeforeJoin": 405, "verdict": "BLOCKED before join -> ran synchronously" }

curl -s -X POST 'localhost:8084/api/demo/async-fire-and-forget?fail=true' | jq
# returns instantly; the exception appears ONLY in the server log
```

Three things interviewers probe, all in
[AsyncConfig.java](src/main/java/com/learning/resilience/config/AsyncConfig.java):

- **The default executor is `SimpleAsyncTaskExecutor`** — a new thread per task, unpooled.
  Always define your own.
- **Pool growth order**: core → *queue* → max. With the default unbounded queue,
  `maxPoolSize` is **never reached**; a bounded queue is what makes it meaningful.
- **`void` @Async swallows exceptions.** Only an `AsyncUncaughtExceptionHandler` sees them.
  A `CompletableFuture` return type surfaces them to the caller.

Plus context propagation: `SecurityContextHolder` and the MDC are ThreadLocals, so they do
**not** cross to the pool thread — and because threads are pooled, a value left behind
leaks into an unrelated later task. Hence the `TaskDecorator`, and its unconditional
`clear()`.

---

## Resilience4j

```bash
curl -X POST 'localhost:8084/api/upstream/mode?failureRate=100'
curl -s 'localhost:8084/api/demo/circuit-breaker?calls=8' | jq
```

```
call  before   after    source
1     CLOSED   CLOSED   fallback:InternalServerError
...
5     CLOSED   OPEN     fallback:InternalServerError
6     OPEN     OPEN     fallback:CallNotPermittedException
7     OPEN     OPEN     fallback:CallNotPermittedException
8     OPEN     OPEN     fallback:CallNotPermittedException

upstreamAttempts: 15    <- 3 per logical call while CLOSED, then ZERO more
```

Once OPEN, `upstreamAttempts` stops rising — the breaker fails fast without touching the
upstream. That is the pattern's entire purpose: stop hammering a service that is already
down, and free your own threads.

Watch it heal:

```bash
curl -X POST 'localhost:8084/api/upstream/mode?failureRate=0'
sleep 6
curl -s localhost:8084/actuator/circuitbreakers | jq '.circuitBreakers.pricingApi.state'  # HALF_OPEN
curl -s localhost:8084/api/demo/retry >/dev/null; curl -s localhost:8084/api/demo/retry >/dev/null
curl -s localhost:8084/api/demo/retry >/dev/null
curl -s localhost:8084/actuator/circuitbreakers | jq '.circuitBreakers.pricingApi.state'  # CLOSED
```

### The aspect-ordering bug — the best story in this module

The first version showed `upstreamAttempts: 5` for 5 calls. **Retry never fired.**

Resilience4j's default order is, outermost first:

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( call ) ) ) ) )
```

With `fallbackMethod` on the **inner** `@CircuitBreaker`, the breaker caught the exception
and **returned the fallback value**. To the outer `Retry` that is a successful call — so
it had nothing to retry. Everything "worked"; retries silently did not exist.

The same default causes a second problem: each retry attempt is recorded by the breaker
separately, so one logical call counts as three failures and the circuit opens ~3× sooner
than configured.

Resilience4j's own fix for Spring Boot 3 — invert the order so the breaker wraps the whole
retry sequence (lower value = further out):

```yaml
resilience4j:
  circuitbreaker:
    circuitBreakerAspectOrder: 1   # outermost
  retry:
    retryAspectOrder: 2            # inside the breaker
```

Now: one logical call → up to 3 upstream attempts → exactly **one** outcome recorded, and
the fallback sits on the outermost annotation where it catches both retry-exhaustion and
`CallNotPermittedException`.
[ResiliencePatternsIT#retryIsActuallyApplied](src/test/java/com/learning/resilience/ResiliencePatternsIT.java)
asserts `attempts == 3`, so this can never silently regress.

### Config worth being able to defend

| Setting | Why |
|---|---|
| `minimumNumberOfCalls: 5` | without it, the first failure is a 100% failure rate and the circuit trips instantly |
| `slowCallDurationThreshold` | a service that is slow but not failing still kills you; pure error-rate breakers miss it entirely |
| `ignoreExceptions` | a 404 means "not found", not "upstream is unhealthy" — counting it takes down a working dependency |
| `enableRandomizedWait` | jitter stops every client retrying in lockstep and turning a blip into an outage |
| retry only idempotent ops | a retried POST can charge a card twice; use an idempotency key |
| `RestClient` timeouts | the default is **infinite** — one hung upstream exhausts your whole request thread pool |

---

## Scheduling

```bash
curl -s localhost:8084/api/demo/scheduled | jq
curl -s localhost:8084/actuator/scheduledtasks | jq
```

- `fixedDelay` — waits N ms after the previous run **finishes**; cannot overlap. Safe default.
- `fixedRate` — starts every N ms regardless; a slow run causes pile-up.
- `cron` — **six** fields in Spring (seconds first), unlike Unix cron's five. Always set `zone`.

Two defaults to know: the scheduler pool size is **1** (one slow job delays every other
job — raised to 4 in `application.yml`), and `@Scheduled` fires on **every instance**, so
three pods run your nightly job three times. Fix with ShedLock, clustered Quartz, or an
external trigger.

---

## Topic → file map

| Topic | File |
|---|---|
| cache abstraction, serializers, per-cache TTL | [CacheConfig.java](src/main/java/com/learning/resilience/config/CacheConfig.java) |
| `@Cacheable` / `@CachePut` / `@CacheEvict` / `@Caching`, key generation | [ProductService.java](src/main/java/com/learning/resilience/service/ProductService.java) |
| executor sizing, queue/max interaction, rejection policy | [AsyncConfig.java](src/main/java/com/learning/resilience/config/AsyncConfig.java) |
| ThreadLocal propagation and pooled-thread leakage | [AsyncConfig.java](src/main/java/com/learning/resilience/config/AsyncConfig.java) |
| `CompletableFuture` vs `void` @Async | [AsyncService.java](src/main/java/com/learning/resilience/service/AsyncService.java) |
| circuit breaker states, retry, bulkhead, rate limiter | [PricingClient.java](src/main/java/com/learning/resilience/service/PricingClient.java) |
| RestTemplate vs WebClient vs RestClient, timeouts | [RestClientConfig.java](src/main/java/com/learning/resilience/config/RestClientConfig.java) |
| fixedRate vs fixedDelay vs cron, distributed scheduling | [ScheduledJobs.java](src/main/java/com/learning/resilience/service/ScheduledJobs.java) |

---

## Tests — 23, all green

```bash
./mvnw -f 04-resilience/pom.xml verify
```

| Class | Covers |
|---|---|
| [CacheIT](src/test/java/com/learning/resilience/CacheIT.java) | hit/miss counts, JSON round-trip, evict semantics, self-invocation |
| [AsyncIT](src/test/java/com/learning/resilience/AsyncIT.java) | real concurrency, future vs void exception visibility |
| [ResiliencePatternsIT](src/test/java/com/learning/resilience/ResiliencePatternsIT.java) | retry count, OPEN→HALF_OPEN→CLOSED, fallback, rate limiting |

---

Previous: [Module 03 — Security](../03-security/) · Next: [Module 05 — Messaging](../05-messaging/)
