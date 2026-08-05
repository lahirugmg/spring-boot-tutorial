package com.learning.coreweb.service;

/** Thrown when the store already holds {@code app.max-tasks} entries. */
public class CapacityExceededException extends RuntimeException {

    private final int limit;

    public CapacityExceededException(int limit) {
        super("Task limit of %d reached".formatted(limit));
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
