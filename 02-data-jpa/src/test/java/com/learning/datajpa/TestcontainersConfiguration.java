package com.learning.datajpa;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres in Docker for every integration test, driven by Colima.
 *
 * INTERVIEW: "How do you test the data layer?"
 *
 * Not with H2. An in-memory database has different SQL dialect quirks, different locking,
 * no real constraint behaviour, and no sequence semantics — so it passes tests your
 * production database would fail. Testcontainers runs the SAME Postgres version you deploy.
 *
 * @ServiceConnection (Boot 3.1+) is the modern wiring. It detects the container type and
 * contributes spring.datasource.url/username/password automatically. Before it you had to
 * write:
 *
 *     @DynamicPropertySource
 *     static void props(DynamicPropertyRegistry registry) {
 *         registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
 *         registry.add("spring.datasource.username", POSTGRES::getUsername);
 *         registry.add("spring.datasource.password", POSTGRES::getPassword);
 *     }
 *
 * Declaring the container as a @Bean (rather than a static @Container field) means its
 * lifecycle is tied to the ApplicationContext — and because the Spring TestContext
 * framework CACHES contexts across test classes, ONE container is started and shared by
 * every test that imports this class. A static @Container field would restart it per class.
 *
 * COLIMA NOTE: Testcontainers needs to find the daemon. This machine already exports
 *     DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
 * and the Makefile also exports
 *     TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
 * which is the path Ryuk (the cleanup sidecar) bind-mounts INSIDE the VM. Without the
 * override Ryuk fails to start and you get confusing "Could not connect to Ryuk" errors.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("appdb")
                .withUsername("app")
                .withPassword("app");
    }
}
