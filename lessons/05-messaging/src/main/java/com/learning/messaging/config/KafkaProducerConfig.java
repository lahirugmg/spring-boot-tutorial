package com.learning.messaging.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Two producers, defined explicitly side by side: a default NON-transactional one for the
 * common fire-and-forget path, and a second TRANSACTIONAL one used only by
 * OrderEventProducer#sendTransactionally.
 *
 * INTERVIEW: "Why not just set transaction-id-prefix on the main producer?"
 * A transactional producer factory can only run one transaction at a time, and every send
 * through it must happen inside executeInTransaction/@Transactional — a plain
 * kafkaTemplate.send() throws "No transaction is in process". Making the DEFAULT producer
 * transactional would break every ordinary send in the app for the sake of one demo.
 *
 * GOTCHA this class exists to demonstrate: Boot's autoconfigured KafkaTemplate/ProducerFactory
 * beans carry @ConditionalOnMissingBean(KafkaTemplate.class) / (ProducerFactory.class) — a
 * TYPE match, not a name match. The moment you define ANY second bean of that type, Boot's
 * autoconfigured one backs off and silently disappears, not just yours-plus-theirs. So once a
 * transactional KafkaTemplate exists here, the "default" one has to be defined explicitly too,
 * built from the same spring.kafka.producer.* properties Boot would otherwise have used.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null));
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
            @Qualifier("producerFactory") ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ProducerFactory<String, Object> transactionalProducerFactory(KafkaProperties properties) {
        var factory = new DefaultKafkaProducerFactory<String, Object>(properties.buildProducerProperties(null));
        // Every producer instance needs a UNIQUE transactional id; Spring appends a suffix
        // to this prefix per instance.
        factory.setTransactionIdPrefix("tx-orders-");
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> transactionalKafkaTemplate(
            @Qualifier("transactionalProducerFactory") ProducerFactory<String, Object> transactionalProducerFactory) {
        return new KafkaTemplate<>(transactionalProducerFactory);
    }
}
