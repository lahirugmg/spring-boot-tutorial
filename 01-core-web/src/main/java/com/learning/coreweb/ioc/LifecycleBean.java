package com.learning.coreweb.ioc;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * INTERVIEW: "Walk me through the Spring bean lifecycle."
 *
 * This bean implements every hook so you can SEE the order in the startup log
 * (and at GET /api/container/lifecycle). The exact sequence is:
 *
 *   1.  constructor                              <- constructor injection happens here
 *   2.  setter / field injection (@Autowired)
 *   3.  *Aware callbacks: BeanNameAware -> BeanClassLoaderAware -> BeanFactoryAware
 *                          -> ...ApplicationContextAware
 *   4.  BeanPostProcessor.postProcessBeforeInitialization()   <- see AuditingBeanPostProcessor
 *   5.  @PostConstruct
 *   6.  InitializingBean.afterPropertiesSet()
 *   7.  custom init-method (@Bean(initMethod="..."))
 *   8.  BeanPostProcessor.postProcessAfterInitialization()    <- AOP proxies are created HERE
 *   ... bean is in service ...
 *   9.  @PreDestroy
 *   10. DisposableBean.destroy()
 *   11. custom destroy-method
 *
 * Two things interviewers love to probe:
 *
 *  - Step 8 is why @Transactional/@Async/@Cacheable are "invisible" to `this` calls: the
 *    proxy wraps the bean AFTER it is built, so an internal `this.method()` call bypasses
 *    the proxy entirely. (Self-invocation problem — demonstrated in modules 02 and 04.)
 *
 *  - Destruction callbacks only fire for singletons. Spring does NOT manage the full
 *    lifecycle of prototype beans; it hands them over and forgets them, so @PreDestroy on
 *    a prototype never runs and you must clean up yourself.
 */
@Component
public class LifecycleBean implements BeanNameAware, ApplicationContextAware,
        InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LifecycleBean.class);

    /** Static so the record survives even as the instance is being torn down. */
    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private String beanName;

    public LifecycleBean() {
        record("1. constructor");
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
        record("3a. BeanNameAware.setBeanName -> " + name);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        record("3b. ApplicationContextAware.setApplicationContext");
    }

    @PostConstruct
    void postConstruct() {
        record("5. @PostConstruct");
    }

    @Override
    public void afterPropertiesSet() {
        record("6. InitializingBean.afterPropertiesSet");
    }

    @PreDestroy
    void preDestroy() {
        record("9. @PreDestroy");
    }

    @Override
    public void destroy() {
        record("10. DisposableBean.destroy");
    }

    public static List<String> events() {
        return List.copyOf(EVENTS);
    }

    static void record(String event) {
        EVENTS.add(event);
        log.info("[lifecycle] {}", event);
    }

    public String beanName() {
        return beanName;
    }
}
