package com.learning.datajpa.repository;

import com.learning.datajpa.entity.Book;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * INTERVIEW: "How do you build a query with six optional filters?"
 *
 * NOT by concatenating strings, and not with sixteen derived-query methods. The options:
 *
 *   Specification (here)  - Criteria API behind a composable interface. Type-safe-ish,
 *                           combines with and()/or(), works with Pageable and Sort.
 *   Querydsl              - nicer DSL, but needs an annotation processor and generated
 *                           Q-classes.
 *   Query by Example      - trivial for equality-only matching, useless beyond that.
 *   A hand-written query  - when the dynamic part is small, honestly the most readable.
 *
 * The idiom below returns null for "no constraint". Spring Data drops null predicates when
 * composing, so callers can chain unconditionally without null checks at every step.
 */
public final class BookSpecifications {

    private BookSpecifications() {}

    public static Specification<Book> titleContains(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return null;
        }
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("title")), "%" + fragment.toLowerCase() + "%");
    }

    public static Specification<Book> priceAtMost(BigDecimal max) {
        if (max == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), max);
    }

    public static Specification<Book> inStock() {
        return (root, query, cb) -> cb.greaterThan(root.get("stock"), 0);
    }

    public static Specification<Book> publishedAfter(LocalDate date) {
        if (date == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThan(root.get("publishedOn"), date);
    }

    /**
     * Joins to the author. Note the `query.getResultType()` guard: a Specification is
     * applied to BOTH the data query and the `count(*)` query that Page performs, and a
     * fetch join on a count query throws. This is the single most common Specification bug.
     */
    public static Specification<Book> authorCountry(String country) {
        if (country == null || country.isBlank()) {
            return null;
        }
        return (root, query, cb) -> {
            if (query != null && Long.class != query.getResultType()) {
                root.fetch("author");         // only fetch-join for the data query
            }
            return cb.equal(root.get("author").get("country"), country);
        };
    }
}
