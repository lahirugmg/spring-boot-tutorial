package com.learning.resilience;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * INTERVIEW: each @Enable* annotation switches on a different AOP-backed feature, and all
 * three share the same proxy semantics (and therefore the same self-invocation blind spot):
 *
 *   @EnableCaching    - registers CacheInterceptor for @Cacheable/@CachePut/@CacheEvict
 *   @EnableAsync      - registers AsyncAnnotationBeanPostProcessor for @Async
 *   @EnableScheduling - registers ScheduledAnnotationBeanPostProcessor for @Scheduled
 *
 * @Scheduled is the odd one out: it does not proxy the bean, it registers the method with
 * a TaskScheduler. That is why @Scheduled works on a private-ish internal method while
 * @Async and @Cacheable must be public and called from outside.
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class ResilienceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResilienceApplication.class, args);
    }
}
