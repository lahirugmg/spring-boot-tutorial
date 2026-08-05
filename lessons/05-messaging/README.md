# Lesson 05 — Messaging

Kafka producer/consumer, delivery guarantees, and dead-letter handling. Needs Kafka on Colima.

```bash
make mq          # kafka on localhost:59092 + kafka-ui on http://localhost:8091
make run-05      # http://localhost:8085
```

---

## Theory: message brokers, partitions, and delivery guarantees

**Why a broker instead of direct calls?** A synchronous HTTP call between two services couples
their availability and their pace: if the callee is down or slow, the caller blocks or fails. A
message broker like Kafka decouples them — a producer publishes an event and moves on; one or
more consumers process it independently, at their own pace, and can be down without the
producer even noticing. The cost is complexity: you trade "did my call succeed" for "will my
message eventually be processed," which is a different, harder set of guarantees to reason
about.

**Topics, partitions, and ordering.** A Kafka topic is a named stream of events, split into
**partitions** for parallelism. Kafka only guarantees ordering *within* a partition, never
across a whole topic. Records are assigned to a partition by hashing their key — the same key
always lands on the same partition — so "all events for order #123 must be processed in order"
becomes, in practice, "key every event by order id." Partition count also caps useful
parallelism: a consumer group can have at most one active consumer per partition, so adding a
fourth consumer to a 3-partition topic leaves it idle.

**Consumer groups.** Consumers subscribe to a topic as part of a named **group**. Kafka
guarantees each partition is consumed by exactly one member of a group — that's how a topic's
work load-balances across instances. Two different consumer groups reading the same topic each
get *every* record independently — that's how a single stream fans out to multiple, unrelated
services.

**Delivery guarantees: pick your failure mode.** Whether a message can be lost or duplicated
comes down to *when* the consumer's offset is committed relative to processing: commit before
processing and a crash mid-processing loses the message (**at-most-once**); commit after
processing — the default, and what you almost always want — and a crash between finishing work
and committing causes redelivery (**at-least-once**, duplicates possible and expected). True
**exactly-once** exists only *within* Kafka, via transactions plus `read_committed` consumers;
the instant a side effect leaves Kafka — a database write, an email, a charge — you're back to
at-least-once, and the fix is making that side effect **idempotent**, not chasing exactly-once
further than Kafka can actually give it to you.

**Producer durability: `acks`.** `acks=0` doesn't wait for any acknowledgment (fastest, least
safe); `acks=1` waits for the partition leader to write the record (safe unless the leader dies
before followers replicate it); `acks=all` waits for every in-sync replica (safest, the
production default). Pair `acks=all` with `enable.idempotence=true` so a producer's own retries
after a lost acknowledgment can't write the same record twice.

**Poison messages and dead-letter topics.** A message a consumer can never successfully
process — a permanently malformed payload, a business-rule violation — fails identically on
every retry. Retrying it forever blocks that partition (Kafka tracks one offset per partition,
so nothing after the poison message gets processed until it's resolved — "head-of-line
blocking"). The standard fix is bounded retries followed by publishing to a **dead-letter
topic**: a parking lot for records a human or a separate process can inspect and replay, so one
bad message degrades gracefully instead of stalling everything behind it.

---

## Drive it end to end

Every response below is real output from running this app against Kafka on Colima — not
illustrative, actually captured.

```bash
B=localhost:8085

curl -s -X POST "$B/api/orders?orderId=order-42&customerId=cust-7&amount=129.99" | jq
```
```json
{
  "eventId": "ebb618ec-6a90-4ff0-9b81-29fb569d2251",
  "orderId": "order-42",
  "partition": 0,
  "offset": 0,
  "note": "the key is orderId, so every event for this order shares a partition"
}
```

```bash
curl -s "$B/api/orders/stats" | jq
```
Immediately after publishing this can still show `totalListenerInvocations: 0` — the send is
asynchronous and the consumer hasn't caught up yet. Give it a second and re-check:
```json
{ "processedOrders": ["order-42"], "uniqueProcessed": 1, "duplicatesSkipped": 0,
  "totalListenerInvocations": 1, "deadLetterCount": 0 }
```

### Ordering: same key → one partition → guaranteed order

```bash
curl -s -X POST "$B/api/orders/ordering" | jq
```
```json
{
  "sameKeyPartitions": [0, 0, 0, 0, 0],
  "differentKeyPartitions": [1, 0, 0, 2, 2],
  "verdict": "identical key -> one partition -> guaranteed order; different keys -> spread -> NO ordering guarantee between them"
}
```
Five events for the same order id all land on partition 0. Five events with five different
order ids scatter across all three partitions — with no ordering guarantee between them at all.

### At-least-once delivery and idempotency

```bash
curl -s -X POST "$B/api/orders/duplicate?orderId=order-dup" | jq   # sends the SAME eventId twice
curl -s "$B/api/orders/stats" | jq
```
```json
{ "processedOrders": ["order-fixed", "order-fixed", "order-fixed", "order-fixed", "order-fixed",
                       "order-1", "order-2", "order-3", "order-4", "order-5", "order-dup"],
  "uniqueProcessed": 11, "duplicatesSkipped": 1, "totalListenerInvocations": 12, "deadLetterCount": 0 }
```
`totalListenerInvocations` (12) is one higher than `uniqueProcessed` (11) — the second,
byte-identical send really was delivered to the listener a second time. The consumer's
`eventId`-keyed dedupe check is what turned that redelivery into a no-op instead of a duplicate
order.

### Poison message → bounded retries → dead-letter topic

```bash
curl -s -X POST "$B/api/orders/poison?orderId=order-poison" | jq
sleep 8
curl -s "$B/api/orders/dead-letters" | jq
```
```json
[
  {
    "key": "order-poison",
    "originalTopic": "orders",
    "reason": "... threw exception; cannot process poisoned order order-poison (attempt 5)"
  }
]
```
The consumer throws on every attempt. **Measured attempt count before the DLT: 5, not 3.** The
backoff (`ExponentialBackOff(500ms, ×2)`, `maxElapsedTime=5000ms`) is bounded by *elapsed time*,
not by a fixed number of retries — with these numbers that works out to 5 delivery attempts in
practice. See the comment on `kafkaErrorHandler` in
[KafkaConsumerConfig.java](src/main/java/com/learning/messaging/config/KafkaConsumerConfig.java)
for the arithmetic; don't trust a "three retries" claim about `ExponentialBackOff` without
checking the actual bound.

### A deterministic failure skips retries entirely

```bash
curl -s -X POST "$B/api/orders/invalid?orderId=order-bad" | jq   # negative amount
sleep 2
curl -s "$B/api/orders/dead-letters" | jq
```
```json
{
  "key": "order-bad",
  "originalTopic": "orders",
  "reason": "... threw exception; negative amount for order order-bad"
}
```
No `(attempt N)` in the reason — because there was only one attempt. `IllegalArgumentException`
is registered as **not retryable** in `KafkaConsumerConfig`, so it goes straight to the DLT.
Retrying a failure that would happen identically every time is pure waste; classifying it saves
four wasted round trips.

### Kafka transactions across two topics

```bash
curl -s -X POST "$B/api/orders/transactional?orderId=order-tx" | jq
```
```json
{ "orderId": "order-tx", "topics": ["orders", "payments"],
  "note": "atomic within Kafka; a DB write in the consumer is still at-least-once" }
```
Both the `orders` and `payments` records become visible together, or neither does. The honest
caveat, straight from
[OrderEventProducer.java](src/main/java/com/learning/messaging/producer/OrderEventProducer.java):
this is exactly-once **within Kafka only** — the moment a consumer's side effect leaves Kafka
(a database write, for instance), you're back to at-least-once.

```bash
curl -s -X POST "$B/api/orders/reset" | jq   # zero the in-memory counters between experiments
```

---

## Watch it in Kafka UI

**http://localhost:8091** — browse the `orders`, `orders.DLT`, and `payments` topics directly:
partition counts, per-partition offsets, consumer group lag, and message contents, without
writing a single consumer yourself.

---

## Topic → file map

| Topic | File |
|---|---|
| topic creation, partition count, why not `auto.create.topics.enable` | [KafkaTopicConfig.java](src/main/java/com/learning/messaging/config/KafkaTopicConfig.java) |
| async send, the three ways to handle a `CompletableFuture`, the ordering key | [OrderEventProducer.java](src/main/java/com/learning/messaging/producer/OrderEventProducer.java) |
| a dedicated transactional producer, and why it has to be separate | [KafkaProducerConfig.java](src/main/java/com/learning/messaging/config/KafkaProducerConfig.java) |
| consumer groups, the idempotency check, poison/invalid handling | [OrderEventConsumer.java](src/main/java/com/learning/messaging/consumer/OrderEventConsumer.java) |
| retry backoff, not-retryable exceptions, dead-letter routing | [KafkaConsumerConfig.java](src/main/java/com/learning/messaging/config/KafkaConsumerConfig.java) |
| schema evolution rules for event payloads | [OrderEvent.java](src/main/java/com/learning/messaging/domain/OrderEvent.java) |
| producer/consumer durability settings, explained inline | [application.yml](src/main/resources/application.yml) |
| every endpoint used above | [MessagingController.java](src/main/java/com/learning/messaging/web/MessagingController.java) |

---

## The bug this lesson was built around

The first version had one `KafkaTemplate`, configured with
`spring.kafka.producer.transaction-id-prefix` set globally so the `/transactional` demo could
call `executeInTransaction(...)`. Every other endpoint — `publish`, `duplicate`, `ordering`,
`poison`, `invalid` — immediately started failing with:

```
IllegalStateException: No transaction is in process; possible solutions: run the template
operation within the scope of a template.executeInTransaction() operation, ...
```

A producer factory with a transaction id prefix makes **every** send through it transactional. A
plain `kafkaTemplate.send()` outside `executeInTransaction`/`@Transactional` throws immediately.
One demo's requirement had silently broken all the others.

The fix looks obvious — add a second, transactional `KafkaTemplate` bean and leave the default
one alone — but that trips a second, less obvious bug:

```
Parameter 0 of method kafkaErrorHandler required a single bean, but 2 were found:
kafkaTemplate, transactionalKafkaTemplate
```

Boot's autoconfigured `KafkaTemplate` carries `@ConditionalOnMissingBean(KafkaTemplate.class)` —
a match by **type**, not by name. The moment a second bean of that type exists anywhere in the
context, Boot's own bean backs off and stops being created at all, not just becomes one of two
options. [KafkaProducerConfig.java](src/main/java/com/learning/messaging/config/KafkaProducerConfig.java)
now defines **both** producer factories and **both** templates explicitly, side by side, with
`@Qualifier` on every injection point that needs a specific one — the default stays cheap and
non-transactional, the second one pays the transactional cost only where atomicity across
`orders` and `payments` is actually required.

If you can explain why adding one bean silently deleted a different, unrelated one, you
understand `@ConditionalOnMissingBean` better than most people who use Spring Boot daily.

---

## A gap, honestly

This lesson doesn't have automated tests yet — the other four each have a Testcontainers-backed
suite; this one doesn't. If you want the exercise, a `TestcontainersConfiguration` with a Kafka
container (mirroring
[lesson 02's](../02-data-jpa/src/test/java/com/learning/datajpa/TestcontainersConfiguration.java))
plus a test that publishes an event and asserts the consumer's counters is a good place to
start — and a real test would have caught the transactional-producer bug above before it ever
reached a terminal.

---

Previous: [Lesson 04 — Caching & Resilience](../04-resilience/)
