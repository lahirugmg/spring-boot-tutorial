package com.learning.coreweb.ioc;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * INTERVIEW: "BeanPostProcessor vs BeanFactoryPostProcessor?"
 *
 *   BeanFactoryPostProcessor - runs FIRST, operates on bean *definitions* (metadata),
 *                              before any instance exists. Example from the framework:
 *                              PropertySourcesPlaceholderConfigurer, which resolves
 *                              ${...} placeholders in definitions.
 *
 *   BeanPostProcessor        - runs per bean *instance*, around initialization. This is
 *                              the extension point that powers @Autowired
 *                              (AutowiredAnnotationBeanPostProcessor), @PostConstruct
 *                              (CommonAnnotationBeanPostProcessor) and all AOP proxying
 *                              (AbstractAutoProxyCreator, in postProcessAfterInitialization).
 *
 * Gotcha worth mentioning: a BeanPostProcessor is itself a bean and must be instantiated
 * very early, so it (and anything it depends on) cannot be proxied or receive
 * @ConfigurationProperties binding reliably. Injecting a heavy dependency into a BPP is
 * how people accidentally disable @Transactional across half their app.
 */
@Component
public class AuditingBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof LifecycleBean) {
            LifecycleBean.record("4. BeanPostProcessor.postProcessBeforeInitialization");
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof LifecycleBean) {
            // Returning a different object here is exactly how Spring swaps in an AOP proxy.
            LifecycleBean.record("8. BeanPostProcessor.postProcessAfterInitialization");
        }
        return bean;
    }
}
