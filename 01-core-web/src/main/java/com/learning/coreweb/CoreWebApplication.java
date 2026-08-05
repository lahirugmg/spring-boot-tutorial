package com.learning.coreweb;

import com.learning.coreweb.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * INTERVIEW: "What does @SpringBootApplication do?"
 *
 * It is a meta-annotation combining three things:
 *
 *   1. @SpringBootConfiguration  - a @Configuration class; also the marker tests use to
 *                                  locate the application context (only ONE per app).
 *   2. @EnableAutoConfiguration  - imports AutoConfigurationImportSelector, which reads
 *                                  META-INF/spring/org.springframework.boot.autoconfigure
 *                                  .AutoConfiguration.imports from every jar on the
 *                                  classpath, then filters those candidates through
 *                                  @Conditional* checks (@ConditionalOnClass,
 *                                  @ConditionalOnMissingBean, @ConditionalOnProperty...).
 *                                  Note: since Boot 2.7 this file replaced the old
 *                                  spring.factories mechanism.
 *   3. @ComponentScan            - scans THIS package and everything below it. That is why
 *                                  the main class lives in the root package. A bean in a
 *                                  sibling package is silently not registered — a very
 *                                  common "why is my bean null" bug.
 *
 * Key ordering fact: auto-configuration runs AFTER user-defined beans are registered.
 * That is what makes @ConditionalOnMissingBean work — "back off if the developer
 * already defined one". Your bean always wins.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class CoreWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreWebApplication.class, args);
    }
}
