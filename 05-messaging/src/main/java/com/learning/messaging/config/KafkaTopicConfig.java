package com.learning.messaging.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declaring topics as beans lets Spring's KafkaAdmin create them at startup.
 *
 * INTERVIEW: "Why not rely on auto.create.topics.enable?"
 * Because auto-created topics get the BROKER's defaults — usually 1 partition and the
 * default replication factor — which is almost never what you want, and a typo in a topic
 * name silently creates a brand-new topic instead of failing. Most production clusters
 * disable auto-creation entirely.
 *
 * PARTITIONS are the unit of parallelism AND of ordering:
 *   - Kafka guarantees order only WITHIN a partition, never across a topic.
 *   - Records with the same key always land on the same partition (default partitioner
 *     hashes the key), so "all events for one order stay ordered" means "key by orderId".
 *   - The max useful consumer count in a group equals the partition count; extra
 *     consumers sit idle.
 *   - You can increase partitions later, but doing so CHANGES THE KEY→PARTITION MAPPING
 *     and breaks ordering for in-flight keys. Size up front.
 */
@Configuration
public class KafkaTopicConfig {

    public static final String ORDERS_TOPIC = "orders";
    public static final String ORDERS_DLT = "orders.DLT";
    public static final String PAYMENTS_TOPIC = "payments";

    @Bean
    NewTopic ordersTopic() {
        return TopicBuilder.name(ORDERS_TOPIC)
                .partitions(3)
                .replicas(1)              // single-node dev cluster; use >=3 in production
                .build();
    }

    /**
     * The dead-letter topic. DefaultErrorHandler + DeadLetterPublishingRecoverer expects
     * "<topic>.DLT" by default and publishes to the SAME partition number, so the DLT needs
     * at least as many partitions as the source topic.
     */
    @Bean
    NewTopic ordersDeadLetterTopic() {
        return TopicBuilder.name(ORDERS_DLT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic paymentsTopic() {
        return TopicBuilder.name(PAYMENTS_TOPIC)
                .partitions(1)            // single partition = total ordering for the topic
                .replicas(1)
                .build();
    }
}
