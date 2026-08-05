package com.learning.coreweb.ioc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * INTERVIEW: "What bean scopes exist and when would you use each?"
 *
 *   singleton (default) - ONE instance per ApplicationContext. Note: per *container*, not
 *                         per JVM and not per classloader — a common trick question.
 *                         Must be thread-safe / stateless.
 *   prototype           - a new instance on every lookup. Spring does not track it, so
 *                         destruction callbacks never run.
 *   request             - one per HTTP request        (web only)
 *   session             - one per HTTP session        (web only)
 *   application         - one per ServletContext      (web only)
 *   websocket           - one per WebSocket session
 *
 * THE classic follow-up: "You inject a prototype bean into a singleton. How many
 * instances do you get?" Answer: ONE. The singleton is wired once at startup, so it keeps
 * the same prototype forever. Fixes, in order of preference:
 *   a) inject ObjectProvider<T> and call getObject() per use   <- cleanest, see below
 *   b) @Lookup method injection
 *   c) a scoped proxy (proxyMode = TARGET_CLASS)
 *   d) inject ApplicationContext and call getBean()            <- service locator, avoid
 */
@Configuration
public class ScopeConfig {

    /** Fresh instance every time it is requested from the container. */
    @Bean
    @Scope("prototype")
    public PrototypeCounter prototypeCounter() {
        return new PrototypeCounter();
    }

    /**
     * @RequestScope is shorthand for
     *   @Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
     *
     * The proxyMode matters: without it, injecting this into a singleton throws
     * "No thread-bound request found" at startup, because there is no request yet.
     * With it, Spring injects a CGLIB proxy that resolves the real instance lazily,
     * per request, off the current thread.
     *
     * Because it is CGLIB-proxied the class must be non-final with a no-arg constructor.
     */
    @Bean
    @RequestScope
    public RequestContext requestContext() {
        return new RequestContext();
    }

    /** Simple stateful bean used to prove prototype semantics. */
    public static class PrototypeCounter {
        private static final AtomicInteger CREATED = new AtomicInteger();
        private final int instanceNumber = CREATED.incrementAndGet();

        public int instanceNumber() {
            return instanceNumber;
        }
    }

    /** Non-final so CGLIB can subclass it for the request-scoped proxy. */
    public static class RequestContext {
        private final String correlationId = UUID.randomUUID().toString().substring(0, 8);

        public String correlationId() {
            return correlationId;
        }
    }
}
