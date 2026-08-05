package com.learning.resilience;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A plain GenericContainer is enough — no redis-specific Testcontainers module needed.
 *
 * @ServiceConnection("redis") tells Boot which ConnectionDetails factory to use, and it
 * contributes spring.data.redis.host/port from the container's mapped port. The name
 * argument is required here precisely BECAUSE this is a GenericContainer: with a
 * dedicated container type (e.g. PostgreSQLContainer) Boot infers it from the type.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection("redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
