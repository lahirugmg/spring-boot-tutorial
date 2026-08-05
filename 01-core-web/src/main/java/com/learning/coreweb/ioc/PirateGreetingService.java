package com.learning.coreweb.ioc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * INTERVIEW: "How does Boot decide whether to create a bean?" — @Conditional.
 *
 * This bean only exists when `app.feature.pirate-mode-enabled=true`.
 * Try it: run with `--app.feature.pirate-mode-enabled=true` and hit
 * GET /api/greetings — the styles list gains "pirate".
 *
 * The @ConditionalOnX family is exactly how auto-configuration works internally:
 *   @ConditionalOnClass       - the jar is on the classpath      (e.g. DataSource.class)
 *   @ConditionalOnMissingBean - the user has not defined one     (back-off / override)
 *   @ConditionalOnProperty    - a flag is set                    (feature toggles)
 *   @ConditionalOnWebApplication / @ConditionalOnBean / @ConditionalOnMissingClass
 *
 * IMPORTANT SUBTLETY: @ConditionalOnMissingBean is evaluated in registration order, so it
 * is only reliable inside auto-configuration (which runs last), never between two of your
 * own @Configuration classes.
 *
 * Debug tip: start with `--debug` to get the AUTO-CONFIGURATION REPORT listing every
 * positive and negative match with the reason.
 */
@Service
@ConditionalOnProperty(name = "app.feature.pirate-mode-enabled", havingValue = "true")
public class PirateGreetingService implements GreetingService {

    @Override
    public String greet(String name) {
        return "Ahoy, %s! Yarr.".formatted(name);
    }

    @Override
    public String style() {
        return "pirate";
    }
}
