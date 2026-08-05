package com.learning.datajpa.repository;

import com.learning.datajpa.entity.Author;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    Optional<Author> findByName(String name);

    List<Author> findByCountry(String country);

    /** Naive: touching author.getBooks() afterwards costs one SELECT per author. */
    @Query("select a from Author a")
    List<Author> findAllTheNaiveWay();

    /**
     * `distinct` matters here. A join fetch across a *-to-many multiplies the parent row
     * once per child, so without it you get 6 authors back as 20 duplicate references.
     * Hibernate 6 de-duplicates entity results automatically, but keeping `distinct` is
     * explicit and portable.
     */
    @Query("select distinct a from Author a left join fetch a.books")
    List<Author> findAllWithBooks();

    /**
     * The same thing declaratively. A LEFT join is used so authors with no books are kept —
     * @EntityGraph defaults to LOAD/FETCH semantics with an outer join, whereas a
     * hand-written `join fetch` is INNER and would silently drop them.
     */
    @EntityGraph(attributePaths = "books")
    @Query("select a from Author a")
    List<Author> findAllWithBooksEntityGraph();

    @Query("""
            select a from Author a
            where size(a.books) >= :minBooks
            order by a.name
            """)
    List<Author> findProlificAuthors(@Param("minBooks") int minBooks);
}
