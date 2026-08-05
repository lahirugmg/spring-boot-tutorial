package com.learning.coreweb.domain;

import java.time.Instant;

/**
 * Immutable domain record. Updates produce a new instance via the `with*` helpers, which
 * keeps the in-memory store safe to share across request threads without locking.
 */
public record Task(
        long id,
        String title,
        String description,
        Priority priority,
        boolean done,
        Instant createdAt
) {
    public Task withUpdates(String title, String description, Priority priority, boolean done) {
        return new Task(id, title, description, priority, done, createdAt);
    }
}
