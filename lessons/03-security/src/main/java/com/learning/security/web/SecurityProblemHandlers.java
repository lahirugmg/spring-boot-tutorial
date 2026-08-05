package com.learning.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * WHY THIS CLASS EXISTS — a genuinely useful thing to be able to explain.
 *
 * Spring Security runs as SERVLET FILTERS, entirely outside the DispatcherServlet. An
 * authentication or authorisation failure is raised by ExceptionTranslationFilter BEFORE
 * any controller is selected, so it never reaches @RestControllerAdvice. That is why
 * adding @ExceptionHandler(AccessDeniedException.class) to a controller advice appears to
 * do nothing for 403s on URL rules.
 *
 * The correct extension points are:
 *   AuthenticationEntryPoint - 401, "you are not authenticated"
 *   AccessDeniedHandler      - 403, "you are authenticated but not permitted"
 *
 * (Exception: AccessDeniedException thrown by METHOD security — @PreAuthorize — happens
 * inside the dispatcher, so that one CAN be caught by a controller advice. Knowing the
 * difference is the sort of detail that lands well.)
 */
@Component
public class SecurityProblemHandlers {

    private final ObjectMapper objectMapper;

    public SecurityProblemHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> write(request, response,
                HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "No valid credentials were supplied. Send an 'Authorization: Bearer <token>' header.",
                "https://example.com/problems/unauthenticated");
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> write(request, response,
                HttpStatus.FORBIDDEN,
                "Access denied",
                "You are authenticated but lack the authority required for this resource.",
                "https://example.com/problems/forbidden");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String title, String detail, String type) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(type));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Deliberately NOT echoing the exception message: it can disclose whether a user
        // exists, which token claim failed, or internal URLs.
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
