package com.learning.datajpa.service;

import com.learning.datajpa.TestcontainersConfiguration;
import com.learning.datajpa.exception.InsufficientStockException;
import com.learning.datajpa.repository.AuditEventRepository;
import com.learning.datajpa.repository.BookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deliberately NOT @DataJpaTest and NOT @Transactional.
 *
 * INTERVIEW-relevant reason: a test annotated @Transactional wraps the whole method in one
 * transaction that is rolled back at the end. That makes it IMPOSSIBLE to observe
 * REQUIRES_NEW correctly — the "independent" inner transaction would commit into a
 * surrounding transaction that never commits, and the assertion would pass or fail for the
 * wrong reason. Rollback and propagation semantics can only be tested with real commits.
 *
 * The cost is that these tests mutate the database, so each one is written to be
 * independent of ordering and of the exact seeded values.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransactionBehaviourIT {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    @DisplayName("REQUIRES_NEW: the audit row survives the caller's rollback")
    void requiresNewSurvivesRollback() {
        long bookId = 1L;
        int stockBefore = bookRepository.findById(bookId).orElseThrow().getStock();
        long auditBefore = auditEventRepository.count();

        assertThatThrownBy(() -> inventoryService.purchase(bookId, 999_999))
                .isInstanceOf(InsufficientStockException.class);

        // Business change rolled back...
        assertThat(bookRepository.findById(bookId).orElseThrow().getStock())
                .as("stock must be unchanged after rollback")
                .isEqualTo(stockBefore);

        // ...but the audit row committed independently.
        assertThat(auditEventRepository.count())
                .as("REQUIRES_NEW commits on its own")
                .isEqualTo(auditBefore + 1);
    }

    @Test
    @DisplayName("REQUIRED: the audit row rolls back with the caller")
    void requiredRollsBackWithCaller() {
        long bookId = 2L;
        long auditBefore = auditEventRepository.count();

        assertThatThrownBy(() -> inventoryService.purchaseWithJoinedAudit(bookId, 999_999))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(auditEventRepository.count())
                .as("REQUIRED joins the caller, so it dies with it")
                .isEqualTo(auditBefore);
    }

    @Test
    @DisplayName("a successful purchase commits via dirty checking, with no save() call")
    void dirtyCheckingCommitsWithoutSave() {
        long bookId = 9L;                       // Effective Java, stock 30
        int before = bookRepository.findById(bookId).orElseThrow().getStock();

        inventoryService.purchase(bookId, 2);

        assertThat(bookRepository.findById(bookId).orElseThrow().getStock()).isEqualTo(before - 2);
    }

    @Test
    @DisplayName("default rollback rule: a CHECKED exception commits the mutation")
    void checkedExceptionCommitsByDefault() {
        long bookId = 3L;
        int before = bookRepository.findById(bookId).orElseThrow().getStock();

        assertThatThrownBy(() -> inventoryService.mutateThenThrowChecked(bookId))
                .isInstanceOf(InventoryService.BusinessException.class);

        assertThat(bookRepository.findById(bookId).orElseThrow().getStock())
                .as("checked exceptions do NOT trigger rollback by default")
                .isEqualTo(before + 100);
    }

    @Test
    @DisplayName("rollbackFor makes the same checked exception roll back")
    void rollbackForFixesIt() {
        long bookId = 4L;
        int before = bookRepository.findById(bookId).orElseThrow().getStock();

        assertThatThrownBy(() -> inventoryService.mutateThenThrowCheckedWithRollbackFor(bookId))
                .isInstanceOf(InventoryService.BusinessException.class);

        assertThat(bookRepository.findById(bookId).orElseThrow().getStock())
                .as("rollbackFor = BusinessException.class")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("self-invocation bypasses the proxy, so no transaction is started")
    void selfInvocationBypassesTheProxy() {
        // Called from outside -> goes through the Spring proxy -> transaction active.
        assertThat(inventoryService.transactionalProbe())
                .as("call through the proxy")
                .isTrue();

        // The bean calls its own @Transactional method with `this` -> interceptor skipped.
        assertThat(inventoryService.selfInvocationTrap())
                .as("`this.method()` never reaches the interceptor")
                .isFalse();
    }
}
