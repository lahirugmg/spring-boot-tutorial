package com.learning.messaging.web;

import com.learning.messaging.consumer.OrderEventConsumer;
import com.learning.messaging.domain.OrderEvent;
import com.learning.messaging.producer.OrderEventProducer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class MessagingController {

    private final OrderEventProducer producer;
    private final OrderEventConsumer consumer;

    public MessagingController(OrderEventProducer producer, OrderEventConsumer consumer) {
        this.producer = producer;
        this.consumer = consumer;
    }

    /** Publish one order event. Same orderId -> same partition -> ordered. */
    @PostMapping
    public Map<String, Object> publish(@RequestParam(defaultValue = "order-1") String orderId,
                                       @RequestParam(defaultValue = "cust-1") String customerId,
                                       @RequestParam(defaultValue = "49.99") BigDecimal amount) {
        var event = OrderEvent.created(UUID.randomUUID().toString(), orderId, customerId, amount);
        var result = producer.sendAsync(event).join();
        var md = result.getRecordMetadata();

        return Map.of(
                "eventId", event.eventId(),
                "orderId", orderId,
                "partition", md.partition(),
                "offset", md.offset(),
                "note", "the key is orderId, so every event for this order shares a partition");
    }

    /**
     * Publishes the SAME eventId twice. At-least-once means duplicates happen; the
     * consumer's dedupe check makes the second one a no-op.
     */
    @PostMapping("/duplicate")
    public Map<String, Object> publishDuplicate(@RequestParam(defaultValue = "order-dup") String orderId) {
        String eventId = UUID.randomUUID().toString();
        var event = new OrderEvent(eventId, orderId, "cust-dup", new BigDecimal("10.00"),
                "CREATED", Instant.now(), 1);

        int before = consumer.duplicatesSkipped();
        producer.sendAsync(event).join();
        producer.sendAsync(event).join();     // byte-identical redelivery

        return Map.of(
                "eventId", eventId,
                "sent", 2,
                "duplicatesSkippedBefore", before,
                "hint", "GET /api/orders/stats in a moment — processed once, 1 duplicate skipped");
    }

    /**
     * Shows ordering. Five events for the SAME orderId all land on one partition and are
     * consumed in order; five events with DIFFERENT keys spread across partitions and have
     * no cross-partition ordering guarantee at all.
     */
    @PostMapping("/ordering")
    public Map<String, Object> orderingDemo() {
        var samePartition = new java.util.ArrayList<Integer>();
        for (int i = 1; i <= 5; i++) {
            var event = OrderEvent.created(UUID.randomUUID().toString(), "order-fixed",
                    "cust-1", new BigDecimal(i + ".00"));
            samePartition.add(producer.sendAsync(event).join().getRecordMetadata().partition());
        }

        var spread = new java.util.ArrayList<Integer>();
        for (int i = 1; i <= 5; i++) {
            var event = OrderEvent.created(UUID.randomUUID().toString(), "order-" + i,
                    "cust-1", new BigDecimal(i + ".00"));
            spread.add(producer.sendAsync(event).join().getRecordMetadata().partition());
        }

        return Map.of(
                "sameKeyPartitions", samePartition,
                "differentKeyPartitions", spread,
                "verdict", "identical key -> one partition -> guaranteed order; "
                        + "different keys -> spread -> NO ordering guarantee between them");
    }

    /** A record the consumer always rejects: retried, then dead-lettered. */
    @PostMapping("/poison")
    public Map<String, Object> poison(@RequestParam(defaultValue = "order-poison") String orderId) {
        producer.sendPoison(orderId).join();
        return Map.of(
                "sent", orderId,
                "expect", "several delivery attempts with exponential backoff (bounded by elapsed time, not a fixed count), then orders.DLT",
                "check", "GET /api/orders/dead-letters after ~6 seconds");
    }

    /** A deterministic failure, classified as NOT retryable -> straight to the DLT. */
    @PostMapping("/invalid")
    public Map<String, Object> invalid(@RequestParam(defaultValue = "order-invalid") String orderId) {
        var event = new OrderEvent(UUID.randomUUID().toString(), orderId, "cust-1",
                new BigDecimal("-5.00"), "CREATED", Instant.now(), 1);
        producer.sendAsync(event).join();
        return Map.of(
                "sent", orderId,
                "expect", "IllegalArgumentException is in addNotRetryableExceptions -> "
                        + "NO retries, immediate DLT");
    }

    /** Kafka transaction spanning two topics: both visible, or neither. */
    @PostMapping("/transactional")
    public Map<String, Object> transactional(@RequestParam(defaultValue = "order-tx") String orderId) {
        var order = OrderEvent.created(UUID.randomUUID().toString(), orderId, "cust-tx",
                new BigDecimal("75.00"));
        var payment = new OrderEvent(UUID.randomUUID().toString(), orderId, "cust-tx",
                new BigDecimal("75.00"), "PAID", Instant.now(), 1);

        producer.sendTransactionally(order, payment);
        return Map.of(
                "orderId", orderId,
                "topics", java.util.List.of("orders", "payments"),
                "note", "atomic within Kafka; a DB write in the consumer is still at-least-once");
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        var result = new LinkedHashMap<String, Object>();
        result.put("processedOrders", consumer.processedOrders());
        result.put("uniqueProcessed", consumer.processedOrders().size());
        result.put("duplicatesSkipped", consumer.duplicatesSkipped());
        result.put("totalListenerInvocations", consumer.totalInvocations());
        result.put("deadLetterCount", consumer.deadLetters().size());
        result.put("note", "totalListenerInvocations > uniqueProcessed means redelivery happened — "
                + "exactly what at-least-once looks like");
        return result;
    }

    @GetMapping("/dead-letters")
    public Object deadLetters() {
        return consumer.deadLetters();
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        consumer.reset();
        return Map.of("status", "counters reset");
    }
}
