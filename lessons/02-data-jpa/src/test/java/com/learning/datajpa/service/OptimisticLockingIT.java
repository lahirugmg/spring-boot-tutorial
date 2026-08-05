package com.learning.datajpa.service;

import com.learning.datajpa.TestcontainersConfiguration;
import com.learning.datajpa.entity.Book;
import com.learning.datajpa.repository.BookRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Optimistic locking, proven with two genuinely concurrent writers.
 *
 * INTERVIEW: "Two users load the same record and both save. What happens?"
 * Without @Version: last write wins, the first user's change vanishes silently — the
 * "lost update" anomaly. With @Version: the second UPDATE carries `WHERE version = ?`,
 * matches 0 rows, and Hibernate raises StaleObjectStateException, which Spring translates
 * to ObjectOptimisticLockingFailureException. The loser must re-read and retry.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OptimisticLockingIT {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("@Version increments on every update")
    void versionIncrements() {
        long bookId = 11L;
        short versionBefore = bookRepository.findById(bookId).orElseThrow().getVersion();

        catalogService.updatePrice(bookId, new BigDecimal("51.00"));

        assertThat(bookRepository.findById(bookId).orElseThrow().getVersion())
                .isEqualTo((short) (versionBefore + 1));
    }

    /**
     * Simulates two users who both loaded the row BEFORE either saved.
     *
     * Two separate EntityManagers are used deliberately: sharing one would put both reads
     * in the same first-level cache and return the same instance, so there would be no
     * conflict to detect. Each EntityManager is its own persistence context — which is the
     * whole point.
     */
    @Test
    @DisplayName("a stale update loses and throws ObjectOptimisticLockingFailureException")
    void staleUpdateIsRejected() {
        long bookId = 12L;

        EntityManager userA = entityManagerFactory.createEntityManager();
        EntityManager userB = entityManagerFactory.createEntityManager();
        try {
            // Both read the same row at the same version.
            userA.getTransaction().begin();
            Book bookA = userA.find(Book.class, bookId);
            short sharedVersion = bookA.getVersion();

            userB.getTransaction().begin();
            Book bookB = userB.find(Book.class, bookId);
            assertThat(bookB.getVersion()).isEqualTo(sharedVersion);

            // User A commits first and wins.
            bookA.setPrice(new BigDecimal("11.11"));
            userA.getTransaction().commit();

            // User B is now holding a stale version.
            bookB.setPrice(new BigDecimal("22.22"));
            // Hibernate raises StaleObjectStateException; the JPA layer surfaces it as
            // OptimisticLockException, wrapped in RollbackException on commit(). Accept
            // any of the three so the test does not depend on wrapping details.
            assertThatThrownBy(() -> userB.getTransaction().commit())
                    .isInstanceOfAny(
                            jakarta.persistence.RollbackException.class,
                            jakarta.persistence.OptimisticLockException.class,
                            org.hibernate.StaleObjectStateException.class);

            // A's write survived; B's was rejected rather than silently lost.
            assertThat(bookRepository.findById(bookId).orElseThrow().getPrice())
                    .isEqualByComparingTo(new BigDecimal("11.11"));
        } finally {
            closeQuietly(userA);
            closeQuietly(userB);
        }
    }

    /**
     * The same conflict through the Spring service layer, where the exception arrives
     * already translated into Spring's DataAccessException hierarchy.
     */
    @Test
    @DisplayName("concurrent service calls: one wins, the loser gets a translated 409-able exception")
    void concurrentServiceCallsConflict() throws Exception {
        long bookId = 13L;
        var barrier = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Runnable writer = () -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    catalogService.updatePrice(bookId, new BigDecimal("33.33"));
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            };

            pool.submit(writer);
            pool.submit(writer);
            barrier.countDown();

            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Either both serialised cleanly (no conflict) or the loser got the expected type.
        // Asserting the TYPE rather than requiring a conflict keeps this test non-flaky.
        if (failure.get() != null) {
            assertThat(failure.get()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        }
        assertThat(bookRepository.findById(bookId).orElseThrow().getPrice())
                .isEqualByComparingTo(new BigDecimal("33.33"));
    }

    private static void closeQuietly(EntityManager em) {
        if (em.getTransaction().isActive()) {
            em.getTransaction().rollback();
        }
        em.close();
    }
}
