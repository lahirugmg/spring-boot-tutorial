package com.learning.coreweb.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * INTERVIEW: "Filter vs Interceptor vs @ControllerAdvice — where does each sit?"
 *
 *   Filter (Servlet spec)      - outermost. Runs before Spring's DispatcherServlet, sees
 *                                every request including static resources and errors.
 *                                Can replace the request/response objects. No knowledge of
 *                                which handler will run. Security lives here.
 *   HandlerInterceptor (Spring)- inside DispatcherServlet. Knows the handler method, so it
 *                                can read annotations on it. preHandle/postHandle/afterCompletion.
 *   @ControllerAdvice          - innermost, around the controller invocation only.
 *
 * Rule of thumb: cross-cutting transport concerns -> filter; handler-aware concerns
 * (auditing a specific annotation, timing a controller) -> interceptor.
 *
 * Extending OncePerRequestFilter rather than implementing Filter guarantees the logic runs
 * exactly once even when the container does a FORWARD/INCLUDE/ASYNC dispatch — a subtle
 * bug source with plain filters.
 */
@Component
@Order(1)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        // MDC puts the id into every log line via the pattern in application.yml.
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // ALWAYS clear in a finally block: servlet threads are pooled and reused, so a
            // leaked MDC value shows up on an unrelated request later. Classic prod bug.
            MDC.remove(MDC_KEY);
        }
    }
}
