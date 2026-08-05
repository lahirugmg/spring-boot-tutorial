package com.learning.datajpa.projection;

import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;

/**
 * INTERVIEW: "Interface projection vs DTO/class projection?"
 *
 *   CLOSED interface projection (only getters matching entity properties, like the first
 *   three here) -> Spring Data narrows the SELECT to just those columns. Cheapest read.
 *
 *   OPEN projection (any getter using @Value/SpEL, like displayLabel below) -> the
 *   optimisation is LOST: Spring Data must load the whole entity to evaluate the
 *   expression. Worth knowing, because adding one convenience getter can silently
 *   turn an efficient query into a full entity load.
 *
 *   Class/record DTO (see BookWithAuthorDto) -> explicit constructor expression, no proxy,
 *   easiest to reason about. Usually the best default.
 */
public interface BookSummary {

    Long getId();

    String getTitle();

    BigDecimal getPrice();

    /** Open projection — forces the full entity to be loaded. Demonstrated deliberately. */
    @Value("#{target.title + ' (' + target.isbn + ')'}")
    String getDisplayLabel();
}
