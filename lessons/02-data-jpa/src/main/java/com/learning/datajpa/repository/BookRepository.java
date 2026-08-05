package com.learning.datajpa.repository;

import com.learning.datajpa.entity.Book;
import com.learning.datajpa.projection.BookSummary;
import com.learning.datajpa.projection.BookWithAuthorDto;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * INTERVIEW: "What's the repository hierarchy in Spring Data?"
 *
 *   Repository            - empty marker
 *   CrudRepository        - save, findById, delete, count
 *   ListCrudRepository    - same but returns List instead of Iterable (Spring Data 3+)
 *   PagingAndSortingRepository - findAll(Pageable), findAll(Sort)
 *   JpaRepository         - adds flush(), saveAndFlush(), deleteAllInBatch(), getReferenceById()
 *
 * There is no implementation class: at startup Spring Data creates a JDK dynamic proxy
 * whose calls are routed by SimpleJpaRepository plus a chain of QueryLookupStrategy
 * objects (declared @Query first, then derived-from-method-name, then named query).
 *
 * Also worth naming: JpaSpecificationExecutor (below) for dynamic/criteria queries — the
 * type-safe answer to "how do you build a filter with 6 optional parameters?".
 */
public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

    // ---------------------------------------------------------------------------------
    // DERIVED QUERIES — the method NAME is parsed into a query at startup.
    // A typo fails the context load, not the request. Keep names short; past about three
    // conditions a @Query or a Specification is far more readable.
    // ---------------------------------------------------------------------------------

    Optional<Book> findByIsbn(String isbn);

    List<Book> findByAuthorIdOrderByPublishedOnDesc(Long authorId);

    List<Book> findByPriceBetweenAndStockGreaterThan(BigDecimal min, BigDecimal max, int minStock);

    boolean existsByIsbn(String isbn);

    long countByAuthorId(Long authorId);

    // ---------------------------------------------------------------------------------
    // THE N+1 PROBLEM AND ITS FIXES
    // ---------------------------------------------------------------------------------

    /**
     * BROKEN ON PURPOSE. Returns Books with a LAZY author proxy. The moment a caller
     * touches book.getAuthor().getName() for each row, Hibernate fires ONE extra SELECT
     * PER BOOK: 1 query for the list + N for the authors = N+1.
     *
     * It is invisible in tests with 2 rows and fatal with 10 000.
     */
    @Query("select b from Book b")
    List<Book> findAllTheNaiveWay();

    /**
     * FIX 1 — JOIN FETCH. One query, author materialised eagerly for this call only.
     * Note `join fetch`, not `join`: a plain join filters but does NOT populate the
     * association, so you still get N+1. This catches a lot of people.
     */
    @Query("select b from Book b join fetch b.author")
    List<Book> findAllWithAuthorJoinFetch();

    /**
     * FIX 2 — @EntityGraph. Declarative, composes with derived queries and Pageable,
     * and does not require you to write the fetch into every JPQL string.
     *
     * IMPORTANT: JOIN FETCH + Pageable is a trap — Hibernate cannot page a fetched
     * collection in SQL, so it pulls the whole result set into memory and pages there
     * (HHH90003004 "firstResult/maxResults specified with collection fetch"). For a
     * *-to-one like this it is fine; for a *-to-many, page the ids first, then fetch.
     */
    @EntityGraph(attributePaths = "author")
    @Query("select b from Book b")
    List<Book> findAllWithAuthorEntityGraph();

    @EntityGraph(attributePaths = "author")
    Page<Book> findByAuthorCountry(String country, Pageable pageable);

    /**
     * FIX 3 — skip entities entirely. If you only need a few columns, a DTO projection is
     * the cheapest option: one query, no persistence context, nothing to lazy-load, and no
     * dirty checking overhead. Reach for this on read-heavy endpoints.
     */
    @Query("""
            select new com.learning.datajpa.projection.BookWithAuthorDto(
                b.id, b.title, b.isbn, b.price, b.stock, a.name, a.country)
            from Book b join b.author a
            """)
    List<BookWithAuthorDto> findAllAsDto();

    /**
     * Interface-based (closed) projection. Spring Data builds the proxy and — importantly —
     * narrows the generated SELECT to just these columns.
     */
    List<BookSummary> findByAuthorId(Long authorId);

    // ---------------------------------------------------------------------------------
    // PAGINATION
    // ---------------------------------------------------------------------------------

    /**
     * INTERVIEW: "Page vs Slice?"
     *   Page  - runs an extra `select count(*)` so you can render "page 3 of 47".
     *   Slice - no count query; only knows whether a next page exists. Cheaper.
     * On very large offsets both degrade, because OFFSET makes the DB walk and discard
     * rows. The scalable answer is KEYSET pagination — see findNextPage below.
     */
    Slice<Book> findByStockGreaterThan(int minStock, Pageable pageable);

    /**
     * Keyset ("seek") pagination: instead of OFFSET 10000, carry the last id you saw.
     * Constant time regardless of depth, at the cost of not being able to jump to page N.
     */
    @Query("select b from Book b where b.id > :afterId order by b.id asc")
    List<Book> findNextPage(@Param("afterId") long afterId, Pageable pageable);

    // ---------------------------------------------------------------------------------
    // LOCKING
    // ---------------------------------------------------------------------------------

    /**
     * PESSIMISTIC_WRITE issues `SELECT ... FOR UPDATE`, holding a row lock until the
     * transaction commits. The timeout hint stops a stuck caller blocking forever
     * (Postgres: raises a lock-timeout error instead of waiting).
     *
     * Must be called inside a transaction — without one there is nothing to hold the lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select b from Book b where b.id = :id")
    Optional<Book> findByIdForUpdate(@Param("id") long id);

    // ---------------------------------------------------------------------------------
    // BULK UPDATE
    // ---------------------------------------------------------------------------------

    /**
     * INTERVIEW: "What's wrong with @Modifying bulk updates?"
     *
     * They run as a single SQL statement and BYPASS the persistence context entirely:
     *   - entities already loaded keep their stale values
     *   - @Version is NOT incremented (so optimistic locking silently degrades)
     *   - no entity callbacks / auditing fire
     *
     * clearAutomatically=true evicts the first-level cache so subsequent reads see fresh
     * data; flushAutomatically=true pushes pending changes to the DB BEFORE the bulk
     * statement so they are not lost. Use both, or don't mix bulk updates with loaded
     * entities in the same transaction.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Book b set b.price = b.price * :factor where b.author.id = :authorId")
    int applyPriceMultiplier(@Param("authorId") long authorId, @Param("factor") BigDecimal factor);
}
