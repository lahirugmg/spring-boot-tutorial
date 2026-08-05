package com.learning.coreweb.service;

/**
 * A plain RuntimeException. It is deliberately NOT annotated with
 * {@code @ResponseStatus(HttpStatus.NOT_FOUND)}.
 *
 * INTERVIEW: "@ResponseStatus on the exception vs @ExceptionHandler in a @ControllerAdvice?"
 *   - @ResponseStatus is quick but couples a domain/service class to the web layer and
 *     gives you no control over the response body.
 *   - A @RestControllerAdvice handler keeps the exception transport-agnostic and lets you
 *     build a proper RFC 7807 body. That is what GlobalExceptionHandler does.
 */
public class TaskNotFoundException extends RuntimeException {

    private final long id;

    public TaskNotFoundException(long id) {
        super("Task %d not found".formatted(id));
        this.id = id;
    }

    public long id() {
        return id;
    }
}
