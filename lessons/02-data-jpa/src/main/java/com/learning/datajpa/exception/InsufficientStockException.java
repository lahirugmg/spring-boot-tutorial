package com.learning.datajpa.exception;

public class InsufficientStockException extends RuntimeException {

    private final long bookId;
    private final int requested;
    private final int available;

    public InsufficientStockException(long bookId, int requested, int available) {
        super("Book %d: requested %d but only %d in stock".formatted(bookId, requested, available));
        this.bookId = bookId;
        this.requested = requested;
        this.available = available;
    }

    public long bookId() { return bookId; }
    public int requested() { return requested; }
    public int available() { return available; }
}
