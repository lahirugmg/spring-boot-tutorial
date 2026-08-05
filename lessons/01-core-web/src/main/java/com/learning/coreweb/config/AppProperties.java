package com.learning.coreweb.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * INTERVIEW: "@Value vs @ConfigurationProperties?"
 *
 *   @Value("${app.max-tasks}")     - one property at a time, SpEL capable, NO relaxed
 *                                    binding, NO validation, hard to test, fails at
 *                                    runtime on typo.
 *   @ConfigurationProperties       - binds a whole tree, type-safe, supports JSR-303
 *                                    validation, relaxed binding, and shows up in
 *                                    /actuator/configprops. Prefer this.
 *
 * "Relaxed binding" means all of these hit the same field `maxTasks`:
 *     app.max-tasks   app.maxTasks   app.max_tasks   APP_MAXTASKS (env var)
 *
 * This is a java record, so binding is CONSTRUCTOR binding: the class is immutable and
 * needs no setters. Register it with @EnableConfigurationProperties (as the main class
 * does) or annotate it @ConfigurationPropertiesScan on the app class.
 *
 * @Validated makes the app FAIL FAST at startup if config is wrong, instead of blowing
 * up at 3am on the first request. Always worth doing.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(

        @NotBlank
        @DefaultValue("english")
        String greetingStyle,

        @Min(1) @Max(10_000)
        @DefaultValue("100")
        int maxTasks,

        @DefaultValue("30s")
        Duration cleanupInterval,

        @DefaultValue
        Feature feature
) {
    /**
     * Nested records bind from `app.feature.*`. No extra annotation needed.
     */
    public record Feature(
            @DefaultValue("false") boolean pirateModeEnabled,
            @DefaultValue("true") boolean requestTimingEnabled
    ) {}
}
