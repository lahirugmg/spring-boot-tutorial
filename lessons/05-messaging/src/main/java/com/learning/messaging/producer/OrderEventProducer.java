package com.learning.messaging.producer;

import com.learning.messaging.config.KafkaTopicConfig;
import com.learning.messaging.domain.OrderEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTemplate<String, Object> transactionalKafkaTemplate;

    public OrderEventProducer(@Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
                               @Qualifier("transactionalKafkaTemplate") KafkaTemplate<String, Object> transactionalKafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.transactionalKafkaTemplate = transactionalKafkaTemplate;
    }

    /**
     * INTERVIEW: "Is KafkaTemplate.send() synchronous?"
     *
     * NO. It buffers the record and returns a CompletableFuture immediately; a background
     * I/O thread batches and transmits. Ignoring the future means you never learn about
     * send failures — the message is silently lost from your application's point of view.
     *
     * Three ways to handle it, in order of throughput:
     *   1. fire and forget       - send() and ignore. Fastest, least safe.
     *   2. asynchronous callback - whenComplete(...) (this method). Good default.
     *   3. synchronous           - .get() (below). Safest, and destroys throughput because
     *                              it defeats batching entirely.
     *
     * (Since Spring Kafka 3.0 this returns java.util.concurrent.CompletableFuture, not the
     * old Spring ListenableFuture — a common compile break when upgrading to Boot 3.)
     */
    public CompletableFuture<SendResult<String, Object>> sendAsync(OrderEvent event) {
        // KEY = orderId. This is the ordering decision: all events for one order hash to
        // the same partition and are therefore consumed in order. A null key would
        // round-robin across partitions and destroy per-order ordering.
        var record = new ProducerRecord<String, Object>(
                KafkaTopicConfig.ORDERS_TOPIC, event.orderId(), event);

        // Headers travel with the record and are readable without deserializing the body —
        // ideal for tracing ids, schema versions and routing hints.
        record.headers().add("eventId", event.eventId().getBytes());
        record.headers().add("schemaVersion", String.valueOf(event.schemaVersion()).getBytes());

        return kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("FAILED to send order {}: {}", event.orderId(), ex.getMessage());
            } else {
                var md = result.getRecordMetadata();
                log.info("Sent order {} -> partition {} offset {}",
                        event.orderId(), md.partition(), md.offset());
            }
        });
    }

    /**
     * Blocking send. Use when the caller genuinely cannot proceed without an ack — but
     * understand the cost: it waits for a full round trip per message and prevents the
     * producer from batching, so throughput collapses.
     */
    public SendResult<String, Object> sendSync(OrderEvent event)
            throws ExecutionException, InterruptedException, TimeoutException {
        return kafkaTemplate.send(KafkaTopicConfig.ORDERS_TOPIC, event.orderId(), event)
                .get(10, TimeUnit.SECONDS);
    }

    /**
     * Sends a record the consumer is guaranteed to reject, to demonstrate retry + DLT.
     * The consumer throws on any event whose status is "POISON".
     */
    public CompletableFuture<SendResult<String, Object>> sendPoison(String orderId) {
        var poison = new OrderEvent("evt-poison-" + orderId, orderId, "cust-x",
                new java.math.BigDecimal("1.00"), "POISON", java.time.Instant.now(), 1);
        return kafkaTemplate.send(KafkaTopicConfig.ORDERS_TOPIC, orderId, poison);
    }

    /**
     * INTERVIEW: "How do you get exactly-once between two topics?"
     *
     * executeInTransaction wraps the sends in a Kafka transaction: either all become
     * visible to read_committed consumers, or none do. Combined with
     * `isolation.level=read_committed` on consumers and a transactional producer, this
     * gives EOS for the consume-transform-produce pattern.
     *
     * The honest caveat: it is exactly-once WITHIN KAFKA. The moment your consumer writes
     * to a database or calls an HTTP API, you are back to at-least-once and you need
     * idempotency at the sink. Saying that unprompted is a strong signal.
     *
     * Uses the SEPARATE transactional template (see KafkaProducerConfig) — the default
     * kafkaTemplate above is deliberately non-transactional.
     */
    public void sendTransactionally(OrderEvent orderEvent, OrderEvent paymentEvent) {
        transactionalKafkaTemplate.executeInTransaction(operations -> {
            operations.send(KafkaTopicConfig.ORDERS_TOPIC, orderEvent.orderId(), orderEvent);
            operations.send(KafkaTopicConfig.PAYMENTS_TOPIC, paymentEvent.orderId(), paymentEvent);
            return true;
        });
    }
}
