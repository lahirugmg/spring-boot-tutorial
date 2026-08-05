package com.learning.datajpa.exception;

public class BookNotFoundException extends RuntimeException {

    private final long id;

    public BookNotFoundException(long id) {
        super("Book %d not found".formatted(id));
        this.id = id;
    }

    public long id() {
        return id;
    }
}
