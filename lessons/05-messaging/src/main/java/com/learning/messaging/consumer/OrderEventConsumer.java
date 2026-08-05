package com.learning.messaging.consumer;

import com.learning.messaging.domain.OrderEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The main consumer. Demonstrates delivery semantics, idempotency and the DLT path.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    /**
     * THE IDEMPOTENCY STORE.
     *
     * INTERVIEW: "What delivery guarantee does Kafka give you?"
     *
     *   at-most-once  - commit the offset BEFORE processing. A crash loses the message.
     *   at-least-once - commit AFTER processing (the default, and what you almost always
     *                   want). A crash between processing and commit REDELIVERS.
     *   exactly-once  - only within Kafka, via transactions + read_committed. As soon as a
     *                   side effect leaves Kafka (a DB write, an email, a payment), it is
     *                   at-least-once again.
     *
     * THE PRACTICAL ANSWER: assume at-least-once and make the CONSUMER IDEMPOTENT. That is
     * what this set does — a dedupe key so reprocessing is harmless. In production this
     * would be a unique constraint on eventId in the database, or a Redis SETNX with a TTL,
     * not an in-memory set (which forgets everything on restart and is per-instance).
     */
    private final Map<String, Boolean> processedEventIds = new ConcurrentHashMap<>();
    private final List<String> processedOrder = new CopyOnWriteArrayList<>();
    private final AtomicInteger duplicatesSkipped = new AtomicInteger();
    private final AtomicInteger totalInvocations = new AtomicInteger();

    /**
     * `groupId` defines the CONSUMER GROUP. Kafka assigns each partition to exactly one
     * consumer in a group, so scaling out means adding instances up to the partition count.
     * Two DIFFERENT group ids both receive every record — that is how you fan out to
     * independent services from one topic.
     *
     * The container factory sets concurrency=3, matching the 3 partitions.
     */
    @KafkaListener(
            topics = "#{T(com.learning.messaging.config.KafkaTopicConfig).ORDERS_TOPIC}",
            groupId = "order-processor",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(@org.springframework.messaging.handler.annotation.Payload OrderEvent event,
                        ConsumerRecord<String, OrderEvent> record,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset,
                        @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer attempt) {

        totalInvocations.incrementAndGet();
        log.info("Received order {} (event {}) partition={} offset={} attempt={}",
                event.orderId(), event.eventId(), partition, offset, attempt);

        // Poison pill: fails every time, exhausts the bounded retries, lands in orders.DLT.
        if ("POISON".equals(event.status())) {
            throw new IllegalStateException(
                    "cannot process poisoned order " + event.orderId() + " (attempt " + attempt + ")");
        }

        // Deterministic failure -> classified as not-retryable, straight to the DLT with
        // no pointless retries.
        if (event.amount() != null && event.amount().signum() < 0) {
            throw new IllegalArgumentException("negative amount for order " + event.orderId());
        }

        // THE IDEMPOTENCY CHECK. putIfAbsent is atomic, so concurrent redeliveries of the
        // same event cannot both pass.
        if (processedEventIds.putIfAbsent(event.eventId(), Boolean.TRUE) != null) {
            duplicatesSkipped.incrementAndGet();
            log.info("DUPLICATE event {} ignored — already processed", event.eventId());
            return;
        }

        processedOrder.add(event.orderId());
        log.info("Processed order {} for customer {}", event.orderId(), event.customerId());
    }

    /**
     * Consumes the dead-letter topic. In production this is where alerting, triage tooling
     * and a manual/automated replay path live — a DLT nobody watches is just a slower way
     * of losing messages.
     */
    @KafkaListener(
            topics = "#{T(com.learning.messaging.config.KafkaTopicConfig).ORDERS_DLT}",
            groupId = "order-dlt-monitor")
    public void consumeDeadLetter(ConsumerRecord<String, Object> record,
                                  @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false)
                                  String exceptionMessage,
                                  @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false)
                                  byte[] originalTopic) {
        deadLettered.add(new DeadLetter(
                String.valueOf(record.key()),
                originalTopic == null ? "unknown" : new String(originalTopic),
                exceptionMessage == null ? "unknown" : exceptionMessage));
        log.warn("DEAD LETTER key={} reason={}", record.key(), exceptionMessage);
    }

    private final List<DeadLetter> deadLettered = new CopyOnWriteArrayList<>();

    public record DeadLetter(String key, String originalTopic, String reason) {}

    public List<String> processedOrders() { return List.copyOf(processedOrder); }
    public int duplicatesSkipped() { return duplicatesSkipped.get(); }
    public int totalInvocations() { return totalInvocations.get(); }
    public List<DeadLetter> deadLetters() { return List.copyOf(deadLettered); }

    public void reset() {
        processedEventIds.clear();
        processedOrder.clear();
        deadLettered.clear();
        duplicatesSkipped.set(0);
        totalInvocations.set(0);
    }
}
