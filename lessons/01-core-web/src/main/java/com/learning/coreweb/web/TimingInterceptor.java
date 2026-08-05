package com.learning.coreweb.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Demonstrates the interceptor's advantage over a filter: {@code handler} is the actual
 * {@link HandlerMethod}, so you can read the controller method and its annotations.
 *
 * Callback contract:
 *   preHandle        - return false to short-circuit; the handler never runs.
 *   postHandle       - after the handler, BEFORE view rendering. Not called if the handler
 *                      threw. With @ResponseBody the body is often already written by the
 *                      message converter here, so mutating the response is unreliable.
 *   afterCompletion  - always runs, even on exception. Put cleanup/timing here.
 *
 * Note this is NOT a @Component: it is declared as a @Bean inside WebConfig. Registering
 * an interceptor is the config class's job, and keeping it out of component scanning means
 * the @WebMvcTest slice behaves identically to the full app.
 */
public class TimingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TimingInterceptor.class);
    private static final String START_TIME = TimingInterceptor.class.getName() + ".start";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Long start = (Long) request.getAttribute(START_TIME);
        if (start == null) {
            return;
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        String handlerName = handler instanceof HandlerMethod hm
                ? hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName()
                : String.valueOf(handler);

        log.info("{} {} -> {} handled by {} in {}ms",
                request.getMethod(), request.getRequestURI(), response.getStatus(), handlerName, millis);
    }
}
