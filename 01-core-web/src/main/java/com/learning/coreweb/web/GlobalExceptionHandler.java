package com.learning.coreweb.web;

import com.learning.coreweb.service.CapacityExceededException;
import com.learning.coreweb.service.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * INTERVIEW: "How do you handle exceptions globally in Spring Boot?"
 *
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody, applied to every controller
 * (narrow it with basePackages/assignableTypes when you need to).
 *
 * WHY EXTEND ResponseEntityExceptionHandler?
 * ------------------------------------------
 * This is the part people get wrong, and it is worth being able to explain.
 *
 * Spring MVC already ships handlers for its own exceptions — 404 NoResourceFoundException,
 * 405 HttpRequestMethodNotSupportedException, 415 HttpMediaTypeNotSupportedException,
 * 400 MethodArgumentNotValidException, and so on. When
 * `spring.mvc.problemdetails.enabled=true`, Boot registers ProblemDetailsExceptionHandler
 * (an @Order(0) advice) to serve them as RFC 7807.
 *
 * If you write a *separate* plain @RestControllerAdvice with its own
 * @ExceptionHandler(MethodArgumentNotValidException.class), yours quietly LOSES: an
 * unordered advice sits at Ordered.LOWEST_PRECEDENCE, so Spring's @Order(0) handler is
 * consulted first and you get its generic "Bad Request" body instead of your field errors.
 *
 * Two ways out, and only one of them is safe:
 *
 *   a) Slap @Order(HIGHEST_PRECEDENCE) on your advice. DON'T — your catch-all
 *      @ExceptionHandler(Exception.class) now also outranks the framework handlers, so a
 *      genuine 404 or 405 gets flattened into a 500.
 *
 *   b) EXTEND ResponseEntityExceptionHandler and override the one hook you care about.
 *      You inherit correct handling of every framework exception and customise a single
 *      case. Within one advice class the MOST SPECIFIC @ExceptionHandler wins, so the
 *      inherited handlers still beat the Exception.class catch-all below.
 *
 * Bonus: Boot's ProblemDetailsErrorHandlingConfiguration is annotated
 * @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class) — so simply declaring
 * this class makes Boot's version back off. A live example of the back-off mechanism that
 * powers all of auto-configuration.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Thrown by @Valid on a @RequestBody, BEFORE the controller method body runs.
     * Collect ALL field errors — returning only the first is a poor API and a very common
     * code-review comment.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.merge(error.getField(),
                        String.valueOf(error.getDefaultMessage()),
                        (a, b) -> a + "; " + b));

        // Object-level errors (class-level constraints) live separately from field errors.
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fieldErrors.merge(error.getObjectName(),
                        String.valueOf(error.getDefaultMessage()),
                        (a, b) -> a + "; " + b));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Validation error");
        problem.setType(URI.create("https://example.com/problems/validation"));
        problem.setProperty("fieldErrors", fieldErrors);
        problem.setProperty("timestamp", Instant.now());

        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ProblemDetail handleNotFound(TaskNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Task not found");
        problem.setType(URI.create("https://example.com/problems/task-not-found"));
        problem.setProperty("taskId", ex.id());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(CapacityExceededException.class)
    public ProblemDetail handleCapacity(CapacityExceededException ex) {
        // 409 Conflict: the request is well-formed but conflicts with current server state.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Capacity exceeded");
        problem.setType(URI.create("https://example.com/problems/capacity-exceeded"));
        problem.setProperty("limit", ex.limit());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Last resort. Log the real detail server-side, return something opaque to the caller —
     * leaking exception messages is an information-disclosure finding in any security review.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error, see server logs");
        problem.setTitle("Internal server error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
