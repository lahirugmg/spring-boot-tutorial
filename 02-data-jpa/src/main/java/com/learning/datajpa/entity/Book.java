package com.learning.datajpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.proxy.HibernateProxy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "book")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "book_seq_gen")
    @SequenceGenerator(name = "book_seq_gen", sequenceName = "book_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, unique = true, length = 20)
    private String isbn;

    /**
     * BigDecimal for money, never double. NUMERIC(10,2) in the schema.
     * Interviewers do ask this; floating point rounding on currency is a real defect class.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "published_on")
    private LocalDate publishedOn;

    @Column(nullable = false)
    private int stock;

    /**
     * INTERVIEW: "FetchType on @ManyToOne?"
     *
     * The JPA DEFAULT for @ManyToOne and @OneToOne is EAGER — which is almost always wrong
     * and is the single biggest source of accidental joins and N+1 in real codebases.
     * ALWAYS write fetch = LAZY explicitly on *-to-one, then fetch what you need per query
     * with a JOIN FETCH or @EntityGraph.
     *
     * Defaults, for the record:
     *   @ManyToOne  -> EAGER   (override to LAZY)
     *   @OneToOne   -> EAGER   (override to LAZY; note a nullable @OneToOne cannot be
     *                           proxied without bytecode enhancement, so it may stay eager)
     *   @OneToMany  -> LAZY    (correct default)
     *   @ManyToMany -> LAZY    (correct default)
     *
     * optional = false tells Hibernate the FK is NOT NULL, which lets it use an inner join
     * instead of a left outer join when it does fetch.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private Author author;

    /**
     * INTERVIEW: "How do you stop two users overwriting each other's changes?"
     *
     * OPTIMISTIC LOCKING. Hibernate adds `AND version = ?` to the UPDATE and bumps the
     * column. If the row was changed by someone else the update affects 0 rows and you get
     * OptimisticLockingFailureException (Spring's translation of Hibernate's
     * StaleObjectStateException). No DB locks are held, so it scales well — the cost is
     * that the loser must retry.
     *
     * Contrast with PESSIMISTIC: SELECT ... FOR UPDATE holds a real row lock for the whole
     * transaction. Correct under high contention on a hot row, but serialises access and
     * risks deadlocks. See InventoryService for both.
     */
    @Version
    private short version;

    protected Book() {
    }

    public Book(String title, String isbn, BigDecimal price, LocalDate publishedOn, int stock) {
        this.title = title;
        this.isbn = isbn;
        this.price = price;
        this.publishedOn = publishedOn;
        this.stock = stock;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getIsbn() { return isbn; }
    public void setIsbn(String isbn) { this.isbn = isbn; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public LocalDate getPublishedOn() { return publishedOn; }
    public void setPublishedOn(LocalDate publishedOn) { this.publishedOn = publishedOn; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
    public Author getAuthor() { return author; }
    public void setAuthor(Author author) { this.author = author; }
    public short getVersion() { return version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> thisClass = this instanceof HibernateProxy p
                ? p.getHibernateLazyInitializer().getPersistentClass() : getClass();
        Class<?> otherClass = o instanceof HibernateProxy p
                ? p.getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        if (!thisClass.equals(otherClass)) return false;
        Book other = (Book) o;
        return id != null && Objects.equals(id, other.getId());
    }

    @Override
    public int hashCode() {
        return Book.class.hashCode();
    }

    @Override
    public String toString() {
        // No `author` here — it is a lazy proxy and touching it would fire a SELECT.
        return "Book{id=%d, title='%s', isbn='%s'}".formatted(id, title, isbn);
    }
}
