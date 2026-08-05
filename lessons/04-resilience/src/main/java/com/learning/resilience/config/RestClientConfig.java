package com.learning.resilience.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * TIMEOUTS ARE NOT OPTIONAL.
     *
     * The default connect/read timeout for the underlying JDK request factory is
     * effectively infinite. One hung upstream then holds a Tomcat worker thread forever;
     * with 200 workers and enough traffic, your service stops responding to everything —
     * a failure entirely caused by someone else's outage.
     *
     * A read timeout shorter than the upstream's own processing time is the single most
     * effective piece of resilience configuration there is, and it costs one line.
     */
    @Bean
    RestClient restClient(RestClient.Builder builder,
                          @Value("${app.upstream.base-url}") String baseUrl) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));

        return builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
