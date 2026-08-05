package com.learning.datajpa.support;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Component;

/**
 * Counts the JDBC statements Hibernate actually prepares, so N+1 stops being a story and
 * becomes a number you can assert on in a test.
 *
 * Requires `spring.jpa.properties.hibernate.generate_statistics: true`.
 * Statistics collection has a real (small) cost — enable it in dev and tests, not in prod.
 *
 * Handy interview line: "I don't argue about N+1 in review, I assert the query count in a
 * test." Nobody can push back on a failing assertion.
 */
@Component
public class QueryCounter {

    private final Statistics statistics;

    public QueryCounter(EntityManagerFactory entityManagerFactory) {
        // The JPA EntityManagerFactory is a Hibernate SessionFactory underneath.
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    public void reset() {
        statistics.clear();
    }

    /** Number of JDBC PreparedStatements created since the last reset(). */
    public long statementCount() {
        return statistics.getPrepareStatementCount();
    }

    public long entityLoadCount() {
        return statistics.getEntityLoadCount();
    }

    public boolean isEnabled() {
        return statistics.isStatisticsEnabled();
    }
}
