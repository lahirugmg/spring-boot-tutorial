package com.learning.datajpa.service;

import com.learning.datajpa.entity.Author;
import com.learning.datajpa.entity.Book;
import com.learning.datajpa.exception.BookNotFoundException;
import com.learning.datajpa.projection.BookSummary;
import com.learning.datajpa.projection.BookWithAuthorDto;
import com.learning.datajpa.repository.AuthorRepository;
import com.learning.datajpa.repository.BookRepository;
import com.learning.datajpa.repository.BookSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class CatalogService {

    private final BookRepository bookRepository;
    private final AuthorRepository authorRepository;

    public CatalogService(BookRepository bookRepository, AuthorRepository authorRepository) {
        this.bookRepository = bookRepository;
        this.authorRepository = authorRepository;
    }

    @Transactional(readOnly = true)
    public Page<Book> search(String title, BigDecimal maxPrice, String country,
                             LocalDate publishedAfter, boolean onlyInStock, Pageable pageable) {
        // Specification.allOf ignores nulls, so every filter is genuinely optional.
        Specification<Book> spec = Specification.allOf(
                BookSpecifications.titleContains(title),
                BookSpecifications.priceAtMost(maxPrice),
                BookSpecifications.authorCountry(country),
                BookSpecifications.publishedAfter(publishedAfter),
                onlyInStock ? BookSpecifications.inStock() : null);

        return bookRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public List<BookWithAuthorDto> listAsDto() {
        return bookRepository.findAllAsDto();
    }

    @Transactional(readOnly = true)
    public List<BookSummary> summariesForAuthor(long authorId) {
        return bookRepository.findByAuthorId(authorId);
    }

    @Transactional(readOnly = true)
    public Book get(long id) {
        return bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
    }

    /**
     * INTERVIEW: "findById vs getReferenceById?"
     *
     *   findById         - SELECTs immediately, returns Optional<T>.
     *   getReferenceById - returns a LAZY PROXY with only the id set, no SQL at all.
     *                      Perfect for setting a foreign key without loading the parent
     *                      (as here). If the row does not exist you get
     *                      EntityNotFoundException at first property access, not at the
     *                      call — which is exactly why you only use it when you already
     *                      know the id is valid.
     *
     * This method uses it to attach a Book to an Author with ONE insert and no select.
     */
    @Transactional
    public Book addBook(long authorId, String title, String isbn, BigDecimal price,
                        LocalDate publishedOn, int stock) {
        Author authorRef = authorRepository.getReferenceById(authorId);
        Book book = new Book(title, isbn, price, publishedOn, stock);
        book.setAuthor(authorRef);
        return bookRepository.save(book);
    }

    /**
     * Demonstrates optimistic locking end to end. The caller passes the version it last
     * saw; if the row moved on, Hibernate's UPDATE matches 0 rows and Spring throws
     * ObjectOptimisticLockingFailureException, which the exception handler maps to 409.
     */
    @Transactional
    public Book updatePrice(long id, BigDecimal newPrice) {
        Book book = bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
        book.setPrice(newPrice);
        return book;            // dirty checking issues the UPDATE ... WHERE version = ?
    }

    @Transactional
    public int repriceAuthorCatalogue(long authorId, BigDecimal factor) {
        return bookRepository.applyPriceMultiplier(authorId, factor);
    }

    /**
     * Returns a DETACHED entity: the transaction ends when this method returns, so the
     * persistence context is closed and `book.getAuthor()` is an uninitialised proxy.
     *
     * With `spring.jpa.open-in-view: false` (set in application.yml), touching it later
     * throws LazyInitializationException. See DemoController#lazyInitialization.
     *
     * INTERVIEW: "What is open-session-in-view and why is it controversial?"
     *   OSIV keeps the Hibernate Session open for the whole HTTP request, so lazy loading
     *   still works inside the view/serialisation layer. Boot enables it BY DEFAULT and
     *   logs a warning about it.
     *   Why it is bad: it holds a DB connection for the entire request including view
     *   rendering, hides N+1 problems until production load, and moves queries out of the
     *   service layer where you can see them. Turn it off and fix the fetch plans properly.
     */
    @Transactional(readOnly = true)
    public Book getDetached(long id) {
        return bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
    }
}
