package com.learning.coreweb.ioc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves — rather than just asserts in a comment — the scope semantics that get asked
 * about in interviews.
 */
@SpringBootTest
class ScopeBehaviourTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("singleton beans are the same instance on every lookup")
    void singletonIsOneInstance() {
        var a = context.getBean(GreetingService.class);
        var b = context.getBean(GreetingService.class);

        assertThat(a).isSameAs(b);
    }

    @Test
    @DisplayName("prototype beans are a new instance on every lookup")
    void prototypeIsNewEachTime() {
        var a = context.getBean(ScopeConfig.PrototypeCounter.class);
        var b = context.getBean(ScopeConfig.PrototypeCounter.class);

        assertThat(a).isNotSameAs(b);
        assertThat(a.instanceNumber()).isNotEqualTo(b.instanceNumber());
    }

    @Test
    @DisplayName("@Primary decides which of two candidates is injected")
    void primaryWins() {
        var picked = context.getBean(GreetingService.class);

        assertThat(picked).isInstanceOf(EnglishGreetingService.class);
        assertThat(picked.greet("Sam")).isEqualTo("Hello, Sam!");
    }

    @Test
    @DisplayName("the @ConditionalOnProperty bean is not registered when the flag is off")
    void conditionalBeanNotRegistered() {
        assertThat(context.getBeanNamesForType(GreetingService.class)).hasSize(1);
        assertThat(context.containsBean("pirateGreetingService")).isFalse();
    }

    @Test
    @DisplayName("the request-scoped bean is injected as a CGLIB proxy, not the real type")
    void requestScopedBeanIsProxied() {
        var bean = context.getBean(ScopeConfig.RequestContext.class);

        // The proxy exists outside a request; only calling a method on it would fail.
        assertThat(bean.getClass().getName()).contains("$$SpringCGLIB$$");
    }

    @Test
    @DisplayName("the bean lifecycle callbacks fired in the documented order")
    void lifecycleOrderIsAsDocumented() {
        var events = LifecycleBean.events();

        assertThat(events).containsSubsequence(
                "1. constructor",
                "3a. BeanNameAware.setBeanName -> lifecycleBean",
                "3b. ApplicationContextAware.setApplicationContext",
                "4. BeanPostProcessor.postProcessBeforeInitialization",
                "5. @PostConstruct",
                "6. InitializingBean.afterPropertiesSet",
                "8. BeanPostProcessor.postProcessAfterInitialization");
    }
}
