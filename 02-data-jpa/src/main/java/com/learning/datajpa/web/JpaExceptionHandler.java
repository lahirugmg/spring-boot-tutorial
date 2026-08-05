package com.learning.datajpa.web;

import com.learning.datajpa.exception.BookNotFoundException;
import com.learning.datajpa.exception.InsufficientStockException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

/**
 * INTERVIEW: "What does @Repository actually add?"
 *
 * It registers PersistenceExceptionTranslationPostProcessor, which converts vendor-specific
 * exceptions (Hibernate's ConstraintViolationException, Postgres SQLState 23505, ...) into
 * Spring's technology-neutral DataAccessException hierarchy. Spring Data repositories get
 * this automatically.
 *
 * The practical payoff is exactly this class: you catch DataIntegrityViolationException
 * once and it works whether you are on Postgres, Oracle or H2 — no SQLState parsing.
 */
@RestControllerAdvice
public class JpaExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BookNotFoundException.class)
    public ProblemDetail handleNotFound(BookNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Book not found");
        problem.setProperty("bookId", ex.id());
        return problem;
    }

    /** Thrown by getReferenceById when the proxied row turns out not to exist. */
    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleEntityNotFound(EntityNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "Referenced entity does not exist: " + ex.getMessage());
        problem.setTitle("Entity not found");
        problem.setProperty("hint", "getReferenceById defers the check to first property access");
        return problem;
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Insufficient stock");
        problem.setProperty("bookId", ex.bookId());
        problem.setProperty("requested", ex.requested());
        problem.setProperty("available", ex.available());
        return problem;
    }

    /**
     * 409 is the right status: the caller's view of the resource is stale, and retrying
     * after a re-read is a sensible client action. Returning 500 here (a common mistake)
     * tells the client to give up on something that is genuinely retryable.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The record was modified by someone else. Re-read it and try again.");
        problem.setTitle("Optimistic lock conflict");
        problem.setType(URI.create("https://example.com/problems/optimistic-lock"));
        problem.setProperty("retryable", true);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail handlePessimisticLock(PessimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not acquire a row lock in time. Try again shortly.");
        problem.setTitle("Lock timeout");
        problem.setProperty("retryable", true);
        return problem;
    }

    /** Unique-constraint and FK violations. Translated by Spring, not parsed by us. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The write violates a database constraint (duplicate key or missing reference).");
        problem.setTitle("Data integrity violation");
        // The root cause carries the constraint name; useful in logs, not for the client.
        problem.setProperty("constraint", ex.getMostSpecificCause().getMessage());
        return problem;
    }
}
