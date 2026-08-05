package com.learning.coreweb.ioc;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * INTERVIEW: "You have two beans of the same type — how does Spring choose?"
 *
 * Resolution order when injecting a single GreetingService:
 *   1. exactly one candidate                       -> use it
 *   2. one candidate marked @Primary               -> use it        <-- this class
 *   3. @Qualifier("name") on the injection point   -> match by name
 *   4. field/parameter name matches a bean name    -> match by name
 *   5. otherwise -> NoUniqueBeanDefinitionException at startup
 *
 * Also worth knowing: @Primary is a *global* default, while @Qualifier is decided at the
 * injection point. Newer code often prefers a custom qualifier annotation
 * (@Retention(RUNTIME) @Qualifier @interface Pirate) over stringly-typed @Qualifier("x").
 */
@Primary
@Service
public class EnglishGreetingService implements GreetingService {

    @Override
    public String greet(String name) {
        return "Hello, %s!".formatted(name);
    }

    @Override
    public String style() {
        return "english";
    }
}
