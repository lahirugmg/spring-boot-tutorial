package com.learning.datajpa.service;

import com.learning.datajpa.entity.Book;
import com.learning.datajpa.exception.BookNotFoundException;
import com.learning.datajpa.exception.InsufficientStockException;
import com.learning.datajpa.repository.BookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Everything about @Transactional that gets asked, demonstrated so you can run it.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final BookRepository bookRepository;
    private final AuditService auditService;

    public InventoryService(BookRepository bookRepository, AuditService auditService) {
        this.bookRepository = bookRepository;
        this.auditService = auditService;
    }

    /**
     * The headline demo. Buy more copies than exist and:
     *   - the stock change ROLLS BACK
     *   - the audit row SURVIVES, because AuditService uses REQUIRES_NEW
     *
     * Also note there is NO bookRepository.save(book) call. Inside a transaction the
     * entity is MANAGED, so Hibernate compares it against its loaded snapshot at flush
     * time and issues the UPDATE itself. That is DIRTY CHECKING.
     *
     * INTERVIEW follow-up: "When DO you need save()?"
     *   - for a NEW (transient) entity, to make it managed
     *   - for a DETACHED entity (loaded in a previous transaction / came from a DTO),
     *     where save() calls merge()
     *   - never for an entity you loaded in the current transaction and mutated
     */
    @Transactional
    public Book purchase(long bookId, int quantity) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookNotFoundException(bookId));

        // Independent transaction: committed immediately, unaffected by our rollback.
        auditService.recordInNewTransaction("PURCHASE_ATTEMPT",
                "book=%d qty=%d stock=%d".formatted(bookId, quantity, book.getStock()));

        if (book.getStock() < quantity) {
            // RuntimeException -> automatic rollback.
            throw new InsufficientStockException(bookId, quantity, book.getStock());
        }

        book.setStock(book.getStock() - quantity);
        // No save(). Dirty checking flushes the UPDATE at commit.
        return book;
    }

    /**
     * Same flow, but the audit joins the caller's transaction — so when the purchase fails
     * the audit row disappears too. Run both endpoints and diff /api/audit to see it.
     */
    @Transactional
    public Book purchaseWithJoinedAudit(long bookId, int quantity) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookNotFoundException(bookId));

        auditService.recordInSameTransaction("PURCHASE_ATTEMPT_JOINED",
                "book=%d qty=%d".formatted(bookId, quantity));

        if (book.getStock() < quantity) {
            throw new InsufficientStockException(bookId, quantity, book.getStock());
        }
        book.setStock(book.getStock() - quantity);
        return book;
    }

    /**
     * INTERVIEW: "Does @Transactional roll back on a checked exception?"
     *
     * NO — and this surprises people. The default rollback rule is:
     *   roll back on RuntimeException and Error;
     *   COMMIT on a checked Exception.
     *
     * The rationale is that a checked exception is part of the method's contract and so is
     * "expected". In practice it is a footgun: throw a checked exception after mutating
     * state and the mutation is COMMITTED.
     *
     * Fix it explicitly with rollbackFor. This method does NOT, on purpose — call
     * /api/inventory/{id}/checked-exception-demo and watch the stock change persist.
     */
    @Transactional
    public void mutateThenThrowChecked(long bookId) throws BusinessException {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookNotFoundException(bookId));
        book.setStock(book.getStock() + 100);
        throw new BusinessException("checked exception after mutation — this COMMITS");
    }

    /** The corrected version: rollbackFor makes the checked exception roll back. */
    @Transactional(rollbackFor = BusinessException.class)
    public void mutateThenThrowCheckedWithRollbackFor(long bookId) throws BusinessException {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookNotFoundException(bookId));
        book.setStock(book.getStock() + 100);
        throw new BusinessException("checked exception after mutation — this ROLLS BACK");
    }

    /**
     * THE SELF-INVOCATION TRAP, proven.
     *
     * This method has no @Transactional. It calls a @Transactional method via `this`,
     * which bypasses the proxy, so NO transaction is started. The returned flag comes
     * straight from Spring's own TransactionSynchronizationManager.
     *
     * Compare with {@link #transactionalProbe()} called directly from the controller
     * (through the proxy), which reports true.
     */
    public boolean selfInvocationTrap() {
        return this.transactionalProbe();      // proxy bypassed -> no transaction
    }

    @Transactional
    public boolean transactionalProbe() {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        log.info("transactionalProbe: actual transaction active = {}", active);
        return active;
    }

    /**
     * PESSIMISTIC locking: SELECT ... FOR UPDATE. The row is locked until this transaction
     * commits, so a concurrent caller blocks (up to the 3s hint on the repository method)
     * rather than failing.
     *
     * INTERVIEW: "Optimistic or pessimistic?"
     *   Optimistic  - low contention, long think-time, want throughput. Loser retries.
     *   Pessimistic - high contention on a hot row (seat booking, account balance) where
     *                 a retry storm would be worse than queuing. Costs a held lock and
     *                 introduces deadlock risk if lock ordering is inconsistent.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Book purchaseWithPessimisticLock(long bookId, int quantity) {
        Book book = bookRepository.findByIdForUpdate(bookId)
                .orElseThrow(() -> new BookNotFoundException(bookId));

        if (book.getStock() < quantity) {
            throw new InsufficientStockException(bookId, quantity, book.getStock());
        }
        book.setStock(book.getStock() - quantity);
        return book;
    }

    /**
     * INTERVIEW: "Isolation levels?"
     *   READ_UNCOMMITTED - dirty reads. Postgres does not implement it (silently READ_COMMITTED).
     *   READ_COMMITTED   - Postgres/Oracle default. No dirty reads; non-repeatable reads possible.
     *   REPEATABLE_READ  - MySQL InnoDB default. Same row reads consistently; in Postgres this
     *                      is snapshot isolation and can fail with a serialization error.
     *   SERIALIZABLE     - full isolation, lowest concurrency.
     *   DEFAULT          - whatever the datasource says. Usually what you want.
     *
     * The anomalies, in order of severity: dirty read -> non-repeatable read -> phantom read.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public int readStockTwice(long bookId) {
        int first = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookNotFoundException(bookId)).getStock();
        // Under REPEATABLE_READ this second read returns `first` even if another
        // transaction committed a change in between.
        int second = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookNotFoundException(bookId)).getStock();
        return second - first;    // always 0 at this isolation level
    }

    /** A plain checked exception, used by the rollback-rule demos above. */
    public static class BusinessException extends Exception {
        public BusinessException(String message) {
            super(message);
        }
    }
}
