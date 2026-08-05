package com.learning.coreweb.ioc;

import com.learning.coreweb.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints that let you *observe* the container behaviour described in this package.
 * Every one of these maps to a question you will be asked out loud.
 */
@RestController
@RequestMapping("/api/container")
public class ContainerController {

    private final GreetingService primaryGreeting;
    private final List<GreetingService> allGreetings;
    private final Map<String, GreetingService> greetingsByBeanName;
    private final ObjectProvider<ScopeConfig.PrototypeCounter> prototypeProvider;
    private final ScopeConfig.PrototypeCounter injectedOncePrototype;
    private final ScopeConfig.RequestContext requestContext;
    private final AppProperties properties;

    /**
     * INTERVIEW: "Constructor vs field vs setter injection — which and why?"
     *
     * Constructor injection (this) is the recommended default because:
     *   - dependencies can be `final` -> genuinely immutable, thread-safe publication
     *   - the object is never in a half-built state
     *   - unresolvable dependencies fail at STARTUP, not at first call
     *   - the class is testable with plain `new` — no reflection, no Spring needed
     *   - a bloated constructor is visible pressure that the class does too much;
     *     @Autowired fields hide that smell
     *   - it makes circular dependencies a hard startup error instead of silently working
     *
     * Field injection (@Autowired on a field) needs reflection to test, cannot be final,
     * and hides dependencies. Setter injection is for genuinely optional collaborators.
     *
     * Since Spring 4.3 a single-constructor class needs NO @Autowired annotation at all.
     */
    public ContainerController(GreetingService primaryGreeting,
                               List<GreetingService> allGreetings,
                               Map<String, GreetingService> greetingsByBeanName,
                               ObjectProvider<ScopeConfig.PrototypeCounter> prototypeProvider,
                               ScopeConfig.PrototypeCounter injectedOncePrototype,
                               ScopeConfig.RequestContext requestContext,
                               AppProperties properties) {
        this.primaryGreeting = primaryGreeting;
        this.allGreetings = allGreetings;
        this.greetingsByBeanName = greetingsByBeanName;
        this.prototypeProvider = prototypeProvider;
        this.injectedOncePrototype = injectedOncePrototype;
        this.requestContext = requestContext;
        this.properties = properties;
    }

    /**
     * Spring can inject ALL beans of a type as a List (ordered by @Order/Ordered) or as a
     * Map keyed by bean name. This is the idiomatic way to build a strategy registry —
     * add a new implementation and it registers itself, no switch statement to edit.
     */
    @GetMapping("/greetings")
    public Map<String, Object> greetings(@RequestParam(defaultValue = "Lahiru") String name) {
        return Map.of(
                "primaryPicked", primaryGreeting.getClass().getSimpleName(),
                "primaryOutput", primaryGreeting.greet(name),
                "allImplementations", allGreetings.stream().map(GreetingService::style).toList(),
                "byBeanName", greetingsByBeanName.keySet(),
                "configuredStyle", properties.greetingStyle(),
                "hint", "restart with --app.feature.pirate-mode-enabled=true to add a bean"
        );
    }

    /**
     * Proves the prototype-in-singleton trap. Call this repeatedly:
     *   injectedOnce  -> ALWAYS the same number (wired once, at startup)
     *   freshFromProvider -> increments every call (a real new instance)
     */
    @GetMapping("/scopes")
    public Map<String, Object> scopes() {
        var result = new LinkedHashMap<String, Object>();
        result.put("singletonController", Integer.toHexString(System.identityHashCode(this)));
        result.put("injectedOncePrototype", injectedOncePrototype.instanceNumber());
        result.put("freshFromProviderA", prototypeProvider.getObject().instanceNumber());
        result.put("freshFromProviderB", prototypeProvider.getObject().instanceNumber());
        // Same value twice within one request, different on the next request.
        result.put("requestScopedFirstRead", requestContext.correlationId());
        result.put("requestScopedSecondRead", requestContext.correlationId());
        result.put("requestScopedProxyClass", requestContext.getClass().getSimpleName());
        return result;
    }

    /** The bean lifecycle, in the order it actually happened at startup. */
    @GetMapping("/lifecycle")
    public List<String> lifecycle() {
        return LifecycleBean.events();
    }
}
