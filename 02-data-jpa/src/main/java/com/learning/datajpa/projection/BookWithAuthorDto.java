package com.learning.datajpa.projection;

import java.math.BigDecimal;

/**
 * Constructor-expression DTO, populated by the {@code select new ...} JPQL in
 * BookRepository#findAllAsDto.
 *
 * The fully-qualified class name in the JPQL is required and is not checked by the
 * compiler — rename or move this record and the query fails at STARTUP (Spring Data
 * validates declared queries when the repository proxy is built). Annoying, but far better
 * than failing on first request.
 */
public record BookWithAuthorDto(
        Long id,
        String title,
        String isbn,
        BigDecimal price,
        int stock,
        String authorName,
        String authorCountry
) {}
