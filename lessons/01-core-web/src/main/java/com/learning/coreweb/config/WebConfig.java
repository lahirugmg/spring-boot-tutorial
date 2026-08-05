package com.learning.coreweb.config;

import com.learning.coreweb.web.TimingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * INTERVIEW: "How do you customise Spring MVC without losing Boot's defaults?"
 *
 * Implement WebMvcConfigurer (this class). Boot's WebMvcAutoConfiguration detects your
 * configurer and calls it IN ADDITION to its own setup.
 *
 * The trap: adding @EnableWebMvc switches off WebMvcAutoConfiguration entirely — you lose
 * static resource handling, the pre-configured Jackson converter, error handling, content
 * negotiation. In a Boot app you almost never want @EnableWebMvc.
 *
 * INTERVIEW: "@Configuration vs @Component for a class with @Bean methods?"
 * @Configuration is proxyBeanMethods=true by default: the class is CGLIB-enhanced so that
 * calling one @Bean method from another returns the SHARED singleton instead of a new
 * object. In a @Component (or with proxyBeanMethods=false) that inter-bean call is a plain
 * Java call and creates a second, unmanaged instance.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean
    public TimingInterceptor timingInterceptor() {
        return new TimingInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (properties.feature().requestTimingEnabled()) {
            // Because this class is @Configuration (proxyBeanMethods=true), calling
            // timingInterceptor() here returns the SAME singleton the container holds,
            // not a second instance. Drop the CGLIB proxy and you would get two.
            registry.addInterceptor(timingInterceptor())
                    .addPathPatterns("/api/**")
                    .excludePathPatterns("/actuator/**");
        }
    }

    /**
     * Injecting a Clock instead of calling Instant.now() directly is what makes
     * time-dependent logic unit-testable: a test supplies Clock.fixed(...) and asserts on
     * an exact timestamp. Cheap habit, always worth mentioning.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
