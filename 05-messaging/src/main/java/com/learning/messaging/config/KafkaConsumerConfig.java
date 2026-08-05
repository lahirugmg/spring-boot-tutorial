package com.learning.messaging.config;

import com.learning.messaging.domain.OrderEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Error handling and retry for consumers — the part of Kafka that separates people who
 * have run it in production from people who have read about it.
 */
@Configuration
public class KafkaConsumerConfig {

    /** Records how many times each key was delivered, so the demo can prove retries. */
    public static final ConcurrentHashMap<String, AtomicInteger> DELIVERY_ATTEMPTS = new ConcurrentHashMap<>();

    /**
     * INTERVIEW: "A consumer throws while processing a record. What happens?"
     *
     * With Spring Kafka's DefaultErrorHandler (the default since 2.8, replacing
     * SeekToCurrentErrorHandler), the container SEEKS BACK to the failed offset and
     * redelivers, blocking the partition until the record succeeds or the retries are
     * exhausted. Then the recoverer runs — here, publishing to a dead-letter topic.
     *
     * WHY THAT MATTERS: retries are BLOCKING. A poison message with infinite retries stops
     * that partition forever — head-of-line blocking, and the classic Kafka outage. Always
     * bound the retries and always have a recoverer.
     *
     * The alternative is NON-BLOCKING retry (@RetryableTopic), which forwards the record to
     * retry topics (orders-retry-0, orders-retry-1, ...) so the main partition keeps
     * flowing. The trade-off is that you LOSE ORDERING for retried records — fine for
     * independent events, wrong for a state machine.
     *
     * NOT-RETRYABLE exceptions: a deserialization failure or a validation error will fail
     * identically every time. Retrying it is pure waste — classify it and send it straight
     * to the DLT.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, Object> kafkaOperations) {

        // Publishes the failed record to <topic>.DLT, on the same partition number.
        // Spring adds headers with the original topic/partition/offset, the exception class,
        // the message and the stack trace — everything needed to triage without guessing.
        var recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".DLT", record.partition()));

        // Exponential backoff, bounded. Three attempts total, then the DLT.
        var backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxElapsedTime(5_000L);

        var errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // These fail deterministically — retrying them just delays the inevitable.
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                org.springframework.kafka.support.serializer.DeserializationException.class);

        errorHandler.setRetryListeners((record, exception, deliveryAttempt) -> {
            String key = String.valueOf(record.key());
            DELIVERY_ATTEMPTS.computeIfAbsent(key, k -> new AtomicInteger()).set(deliveryAttempt);
        });

        return errorHandler;
    }

    /**
     * The listener container factory.
     *
     * `concurrency` is the number of consumer threads in this application instance. Each
     * gets a disjoint subset of partitions, so concurrency above the partition count leaves
     * threads idle. Three partitions -> concurrency 3 is the sweet spot here.
     *
     * Boot's ConcurrentKafkaListenerContainerFactoryConfigurer applies all the
     * spring.kafka.* properties first, so this only layers on what is not expressible
     * in configuration.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {

        // <Object, Object> because that is the signature Boot's configurer requires; the
        // concrete payload type is resolved per-listener by the JSON message converter.
        // Defining a bean with this exact NAME overrides Boot's auto-configured factory.
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(kafkaErrorHandler);

        // Exposes the delivery attempt to @Header(KafkaHeaders.DELIVERY_ATTEMPT) in listeners.
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        return factory;
    }

    /** Helper for the demo endpoints. */
    public static int deliveryAttemptsFor(ConsumerRecord<?, ?> record) {
        var counter = DELIVERY_ATTEMPTS.get(String.valueOf(record.key()));
        return counter == null ? 0 : counter.get();
    }
}
