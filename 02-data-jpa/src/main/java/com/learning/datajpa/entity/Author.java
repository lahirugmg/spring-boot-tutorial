package com.learning.datajpa.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.proxy.HibernateProxy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * INTERVIEW: "IDENTITY vs SEQUENCE for the primary key — does it matter?"
 *
 * Yes, a lot, and this is a favourite senior-level question.
 *
 *   GenerationType.IDENTITY - relies on the DB auto-increment. Hibernate MUST execute the
 *                             INSERT immediately on persist() to learn the id, which
 *                             DISABLES JDBC batch inserts entirely. Fine for low volume,
 *                             terrible for bulk writes.
 *   GenerationType.SEQUENCE - Hibernate asks the sequence for ids up front, so inserts can
 *                             be queued and batched at flush time. With allocationSize=50
 *                             it uses a "pooled" optimizer and hits the sequence once per
 *                             50 rows instead of once per row.
 *   GenerationType.AUTO     - on Postgres resolves to SEQUENCE; on MySQL to IDENTITY.
 *                             Being explicit is better than being surprised.
 *
 * THE TRAP: allocationSize here must match INCREMENT BY on the actual DB sequence. If the
 * sequence increments by 1 but Hibernate thinks it owns 50 ids, you get duplicate-key
 * violations under concurrency. See V1__init.sql — the migration declares INCREMENT BY 50.
 */
@Entity
@Table(name = "author")
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "author_seq_gen")
    @SequenceGenerator(name = "author_seq_gen", sequenceName = "author_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 100)
    private String country;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * INTERVIEW: "@OneToMany — what do mappedBy, cascade and orphanRemoval do?"
     *
     *   mappedBy = "author"  -> Book owns the FK. Without it JPA assumes a join TABLE and
     *                           creates author_book, which is almost never what you want.
     *   cascade = ALL        -> persist/merge/remove flow to children. Only safe when the
     *                           children genuinely cannot exist without the parent.
     *   orphanRemoval = true -> removing a Book from this set DELETEs the row. Different
     *                           from CascadeType.REMOVE, which only fires when the PARENT
     *                           is deleted.
     *
     * Set rather than List: with a List, Hibernate cannot dedupe and a JOIN FETCH across
     * two collections produces a cartesian product (MultipleBagFetchException). Set also
     * makes `DELETE + re-INSERT all rows` on a @OneToMany update far less likely.
     *
     * FetchType.LAZY is the default for @OneToMany and you should keep it.
     */
    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<Book> books = new LinkedHashSet<>();

    protected Author() {
        // JPA requires a no-arg constructor; `protected` keeps it out of your API.
    }

    public Author(String name, String country) {
        this.name = name;
        this.country = country;
    }

    /**
     * Always mutate BOTH sides of a bidirectional association through a helper. Setting
     * only one side leaves the in-memory graph inconsistent with what gets flushed, which
     * produces bugs that only appear after the first-level cache is cleared.
     */
    public void addBook(Book book) {
        books.add(book);
        book.setAuthor(this);
    }

    public void removeBook(Book book) {
        books.remove(book);
        book.setAuthor(null);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<Book> getBooks() { return books; }

    /**
     * INTERVIEW: "How do you implement equals/hashCode on a JPA entity?"
     *
     * The rules that actually matter:
     *   1. hashCode() must be CONSTANT across the entity's lifecycle. A generated id is
     *      null before persist and set after, so hashing on the id breaks any entity you
     *      put in a HashSet before saving. Return a constant from the class instead.
     *   2. Use `getClass()` carefully — a lazy Hibernate proxy is a SUBCLASS, so
     *      `getClass() != o.getClass()` wrongly reports unequal. Unwrap via HibernateProxy
     *      (below) or use `instanceof`.
     *   3. Never include mutable business fields.
     *
     * The genuinely simplest alternative, worth saying out loud: assign a UUID in the
     * constructor and use that as a business key, sidestepping the whole problem.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> thisClass = this instanceof HibernateProxy p
                ? p.getHibernateLazyInitializer().getPersistentClass() : getClass();
        Class<?> otherClass = o instanceof HibernateProxy p
                ? p.getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        if (!thisClass.equals(otherClass)) return false;
        Author other = (Author) o;
        return id != null && Objects.equals(id, other.getId());
    }

    @Override
    public int hashCode() {
        // Constant on purpose — see rule 1 above.
        return Author.class.hashCode();
    }

    @Override
    public String toString() {
        // NEVER include `books` here: toString() on a lazy collection triggers a query,
        // or a LazyInitializationException outside a session. A logged toString() is a
        // classic accidental-N+1 source.
        return "Author{id=%d, name='%s'}".formatted(id, name);
    }
}
