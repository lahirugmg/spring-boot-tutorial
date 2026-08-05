package com.learning.datajpa.service;

import com.learning.datajpa.entity.Author;
import com.learning.datajpa.entity.Book;
import com.learning.datajpa.repository.AuthorRepository;
import com.learning.datajpa.repository.BookRepository;
import com.learning.datajpa.support.QueryCounter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * THE N+1 PROBLEM, measured.
 *
 * Every method here does the same logical thing — list books with their author's name —
 * and reports how many SQL statements it took. Hit GET /api/demo/fetch-strategies and
 * compare. With 20 seeded books the naive version costs 7 statements (1 + 6 distinct
 * authors, thanks to the first-level cache deduplicating repeats); the fixes cost 1.
 *
 * INTERVIEW: "How do you detect N+1 in the first place?"
 *   - Hibernate statistics / query counts asserted in a test (what this class does)
 *   - `spring.jpa.show-sql` or the org.hibernate.SQL logger in dev
 *   - datasource-proxy or p6spy for per-request counts
 *   - APM traces showing a fan-out of identical queries
 *   - the honest answer: it never shows up in dev with 5 rows, so you need the assertion
 */
@Service
public class FetchStrategyService {

    /** What one strategy cost. `statements` is the number that matters. */
    public record StrategyResult(String strategy, int rows, long statements, String verdict) {}

    private final BookRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final QueryCounter queryCounter;

    /**
     * A reference to our own PROXY, used by runAll(). See the comment on that method —
     * calling this.naiveLazy() there would defeat the entire demo.
     *
     * ObjectProvider defers the lookup, so this is not a circular-dependency problem.
     * (@Lazy on a self-injected constructor param works too; injecting the raw type
     * directly would fail with "requested bean is currently in creation".)
     */
    private final ObjectProvider<FetchStrategyService> self;

    public FetchStrategyService(BookRepository bookRepository,
                                AuthorRepository authorRepository,
                                QueryCounter queryCounter,
                                ObjectProvider<FetchStrategyService> self) {
        this.bookRepository = bookRepository;
        this.authorRepository = authorRepository;
        this.queryCounter = queryCounter;
        this.self = self;
    }

    /**
     * readOnly = true is not just documentation:
     *   - Hibernate sets FlushMode.MANUAL, so it skips dirty-checking the whole
     *     persistence context at commit — measurably cheaper on big result sets
     *   - the JDBC connection is marked read-only, which some drivers/proxies use to
     *     route the query to a read replica
     * Use it on every query-only service method.
     */
    @Transactional(readOnly = true)
    public StrategyResult naiveLazy() {
        queryCounter.reset();
        List<Book> books = bookRepository.findAllTheNaiveWay();
        // This innocent-looking line is the bug: one SELECT per distinct author.
        books.forEach(book -> book.getAuthor().getName());
        return new StrategyResult("1-naive-lazy", books.size(), queryCounter.statementCount(),
                "N+1: 1 query for books, then one per distinct author");
    }

    @Transactional(readOnly = true)
    public StrategyResult joinFetch() {
        queryCounter.reset();
        List<Book> books = bookRepository.findAllWithAuthorJoinFetch();
        books.forEach(book -> book.getAuthor().getName());
        return new StrategyResult("2-join-fetch", books.size(), queryCounter.statementCount(),
                "single query; author eagerly materialised for this call only");
    }

    @Transactional(readOnly = true)
    public StrategyResult entityGraph() {
        queryCounter.reset();
        List<Book> books = bookRepository.findAllWithAuthorEntityGraph();
        books.forEach(book -> book.getAuthor().getName());
        return new StrategyResult("3-entity-graph", books.size(), queryCounter.statementCount(),
                "same SQL as join fetch, declarative and composes with Pageable");
    }

    @Transactional(readOnly = true)
    public StrategyResult dtoProjection() {
        queryCounter.reset();
        var dtos = bookRepository.findAllAsDto();
        return new StrategyResult("4-dto-projection", dtos.size(), queryCounter.statementCount(),
                "cheapest: no entities, no persistence context, no dirty checking");
    }

    /**
     * The *-to-many version of the same problem, which is worse: fetching a collection
     * eagerly multiplies rows, and you can only JOIN FETCH one collection per query
     * (a second one throws MultipleBagFetchException with List, or silently produces a
     * cartesian product).
     */
    @Transactional(readOnly = true)
    public StrategyResult naiveCollection() {
        queryCounter.reset();
        List<Author> authors = authorRepository.findAllTheNaiveWay();
        authors.forEach(author -> author.getBooks().size());
        return new StrategyResult("5-collection-naive", authors.size(), queryCounter.statementCount(),
                "N+1 on a @OneToMany: one SELECT per author's book collection");
    }

    @Transactional(readOnly = true)
    public StrategyResult collectionJoinFetch() {
        queryCounter.reset();
        List<Author> authors = authorRepository.findAllWithBooks();
        authors.forEach(author -> author.getBooks().size());
        return new StrategyResult("6-collection-join-fetch", authors.size(), queryCounter.statementCount(),
                "one query; note `distinct` to collapse the duplicated parent rows");
    }

    /**
     * Runs every strategy and returns the comparison table.
     *
     * TWO deliberate design decisions here, both of which are interview answers:
     *
     * 1. This method is NOT @Transactional. Each strategy must get its OWN transaction and
     *    therefore its own persistence context. Wrap them all in one transaction and the
     *    first-level cache makes every strategy after the first look free — naiveLazy()
     *    would load all 6 authors, and the "fixes" would then report 1 statement not
     *    because they are efficient but because nothing was left to load. The measurement
     *    would be meaningless.
     *
     * 2. It calls self.getObject().naiveLazy(), NOT this.naiveLazy().
     *
     *    THE SELF-INVOCATION TRAP — the most commonly asked Spring gotcha:
     *    @Transactional is implemented with a PROXY that wraps this bean. Callers go
     *    through the proxy, which opens a transaction and then delegates to the target.
     *    But `this` inside the target is the RAW object, not the proxy — so an internal
     *    `this.someTransactionalMethod()` call bypasses the interceptor completely and
     *    runs with NO transaction. Same for @Cacheable, @Async, @Retryable, @PreAuthorize.
     *
     *    Fixes, best first:
     *      a) move the method to a different bean (usually the right design answer)
     *      b) inject a reference to yourself and call through it (this method)
     *      c) AopContext.currentProxy(), needs @EnableAspectJAutoProxy(exposeProxy = true)
     *      d) switch to AspectJ load-time weaving — no proxies, so no problem at all
     *
     *    InventoryService#selfInvocationTrap proves the failure empirically.
     */
    public List<StrategyResult> runAll() {
        FetchStrategyService proxy = self.getObject();
        return List.of(
                proxy.naiveLazy(),
                proxy.joinFetch(),
                proxy.entityGraph(),
                proxy.dtoProjection(),
                proxy.naiveCollection(),
                proxy.collectionJoinFetch());
    }
}
