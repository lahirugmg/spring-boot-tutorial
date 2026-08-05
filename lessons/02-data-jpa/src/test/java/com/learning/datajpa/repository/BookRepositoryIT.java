package com.learning.datajpa.repository;

import com.learning.datajpa.TestcontainersConfiguration;
import com.learning.datajpa.entity.Book;
import com.learning.datajpa.support.QueryCounter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @DataJpaTest is the persistence SLICE: entities, repositories, DataSource, Flyway and a
 * transaction manager — no controllers, no services, no web server.
 *
 * Two behaviours to know:
 *   1. It is @Transactional, so every test ROLLS BACK at the end. Tests never pollute each
 *      other and you need no cleanup. (The flip side: it hides bugs that only appear on a
 *      real commit — see TransactionBehaviourIT, which deliberately does not use this.)
 *   2. It tries to replace your DataSource with an embedded one. @AutoConfigureTestDatabase
 *      (replace = NONE) stops that so the Testcontainers Postgres is used instead.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, QueryCounter.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BookRepositoryIT {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private AuthorRepository authorRepository;

    @Autowired
    private QueryCounter queryCounter;

    @Autowired
    private EntityManager entityManager;

    /**
     * Because the whole test method runs in ONE transaction, the persistence context is
     * shared between repository calls. Without clearing it the first-level cache would
     * serve the second query for free and every count would be wrong.
     */
    @BeforeEach
    void clearPersistenceContextAndStats() {
        entityManager.flush();
        entityManager.clear();
        queryCounter.reset();
    }

    @Test
    @DisplayName("statistics are enabled — otherwise every count assertion below is vacuous")
    void statisticsAreEnabled() {
        assertThat(queryCounter.isEnabled())
                .as("set spring.jpa.properties.hibernate.generate_statistics=true")
                .isTrue();
    }

    @Test
    @DisplayName("Flyway seeded 20 books across 6 authors")
    void flywayRanTheSeedMigration() {
        assertThat(bookRepository.count()).isEqualTo(20);
        assertThat(authorRepository.count()).isEqualTo(6);
    }

    @Test
    @DisplayName("N+1: the naive fetch costs 1 + one query per distinct author")
    void naiveFetchCausesNPlusOne() {
        List<Book> books = bookRepository.findAllTheNaiveWay();
        assertThat(books).hasSize(20);

        long afterListQuery = queryCounter.statementCount();
        assertThat(afterListQuery).isEqualTo(1);

        // Touching the lazy association is what triggers the extra selects.
        books.forEach(book -> book.getAuthor().getName());

        // 1 for the books + 6 distinct authors. Not 21, because the first-level cache
        // dedupes repeat authors within the same persistence context — which is exactly
        // why N+1 looks harmless in small tests and explodes on wide data.
        assertThat(queryCounter.statementCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("JOIN FETCH collapses it to a single query")
    void joinFetchIsOneQuery() {
        List<Book> books = bookRepository.findAllWithAuthorJoinFetch();
        books.forEach(book -> book.getAuthor().getName());

        assertThat(books).hasSize(20);
        assertThat(queryCounter.statementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("@EntityGraph produces the same single query as JOIN FETCH")
    void entityGraphIsOneQuery() {
        List<Book> books = bookRepository.findAllWithAuthorEntityGraph();
        books.forEach(book -> book.getAuthor().getName());

        assertThat(books).hasSize(20);
        assertThat(queryCounter.statementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a DTO projection is one query and loads no entities at all")
    void dtoProjectionLoadsNoEntities() {
        var dtos = bookRepository.findAllAsDto();

        assertThat(dtos).hasSize(20);
        assertThat(dtos.getFirst().authorName()).isNotBlank();
        assertThat(queryCounter.statementCount()).isEqualTo(1);
        // The decisive difference: nothing entered the persistence context.
        assertThat(queryCounter.entityLoadCount()).isZero();
    }

    @Test
    @DisplayName("the @OneToMany side N+1s the same way, and `distinct` join fetch fixes it")
    void collectionFetchStrategies() {
        var naive = authorRepository.findAllTheNaiveWay();
        naive.forEach(author -> author.getBooks().size());
        assertThat(queryCounter.statementCount()).isEqualTo(7);   // 1 + 6 collections

        entityManager.clear();
        queryCounter.reset();

        var fetched = authorRepository.findAllWithBooks();
        fetched.forEach(author -> author.getBooks().size());
        assertThat(fetched).hasSize(6);                            // distinct collapsed the duplicates
        assertThat(queryCounter.statementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("derived query methods parse the method name into a query")
    void derivedQueries() {
        assertThat(bookRepository.findByIsbn("978-0134685991"))
                .isPresent()
                .get()
                .extracting(Book::getTitle)
                .isEqualTo("Effective Java");

        assertThat(bookRepository.existsByIsbn("nope-nope")).isFalse();
        assertThat(bookRepository.countByAuthorId(2L)).isEqualTo(4);
        assertThat(bookRepository.findByPriceBetweenAndStockGreaterThan(
                new BigDecimal("40.00"), new BigDecimal("50.00"), 10))
                .isNotEmpty()
                .allSatisfy(book -> {
                    assertThat(book.getPrice()).isBetween(new BigDecimal("40.00"), new BigDecimal("50.00"));
                    assertThat(book.getStock()).isGreaterThan(10);
                });
    }

    @Test
    @DisplayName("Page runs an extra count query; Slice does not")
    void pageVersusSlice() {
        var page = bookRepository.findByAuthorCountry("United States",
                PageRequest.of(0, 5, Sort.by("title")));
        long afterPage = queryCounter.statementCount();

        assertThat(page.getContent()).hasSize(5);
        // Seed data: 4 US authors (ids 2-5) owning 4 + 3 + 4 + 2 = 13 books.
        assertThat(page.getTotalElements()).isEqualTo(13);
        assertThat(afterPage).as("1 data query + 1 count query").isEqualTo(2);

        queryCounter.reset();
        var slice = bookRepository.findByStockGreaterThan(0, PageRequest.of(0, 5, Sort.by("id")));

        assertThat(slice.getContent()).hasSize(5);
        assertThat(slice.hasNext()).isTrue();
        // Slice fetches size+1 rows to know whether a next page exists — no count query.
        assertThat(queryCounter.statementCount()).as("no count query for a Slice").isEqualTo(1);
    }

    @Test
    @DisplayName("keyset pagination walks by id with no OFFSET")
    void keysetPagination() {
        var firstPage = bookRepository.findNextPage(0L, PageRequest.ofSize(5));
        assertThat(firstPage).hasSize(5);

        long lastSeen = firstPage.getLast().getId();
        var secondPage = bookRepository.findNextPage(lastSeen, PageRequest.ofSize(5));

        assertThat(secondPage).hasSize(5);
        assertThat(secondPage.getFirst().getId()).isGreaterThan(lastSeen);
        assertThat(secondPage).extracting(Book::getId).doesNotContainAnyElementsOf(
                firstPage.stream().map(Book::getId).toList());
    }

    @Test
    @DisplayName("a Specification composes optional filters and drops the null ones")
    void specificationsCompose() {
        var spec = org.springframework.data.jpa.domain.Specification.allOf(
                BookSpecifications.titleContains("clean"),
                BookSpecifications.priceAtMost(new BigDecimal("45.00")),
                null,                                   // an absent filter
                BookSpecifications.inStock());

        var results = bookRepository.findAll(spec);

        assertThat(results).isNotEmpty().allSatisfy(book -> {
            assertThat(book.getTitle().toLowerCase()).contains("clean");
            assertThat(book.getPrice()).isLessThanOrEqualTo(new BigDecimal("45.00"));
            assertThat(book.getStock()).isPositive();
        });
    }

    @Test
    @DisplayName("a bulk @Modifying update bypasses the persistence context")
    void bulkUpdateBypassesPersistenceContext() {
        int updated = bookRepository.applyPriceMultiplier(6L, new BigDecimal("2.0"));
        assertThat(updated).isEqualTo(3);

        // clearAutomatically=true evicted the context, so this re-reads from the DB.
        var books = bookRepository.findByAuthorIdOrderByPublishedOnDesc(6L);
        assertThat(books).allSatisfy(book ->
                assertThat(book.getPrice()).isGreaterThan(new BigDecimal("30.00")));

        // The version column was NOT bumped — bulk updates skip optimistic locking.
        assertThat(books).allSatisfy(book -> assertThat(book.getVersion()).isZero());
    }
}
