package com.learning.datajpa.web.dto;

import com.learning.datajpa.entity.Book;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class BookDtos {

    private BookDtos() {}

    public record BookResponse(
            Long id,
            String title,
            String isbn,
            BigDecimal price,
            LocalDate publishedOn,
            int stock,
            Long authorId,
            short version
    ) {
        /**
         * NOTE the `book.getAuthor().getId()` call. On an uninitialised Hibernate proxy
         * this does NOT hit the database: the proxy was constructed from the FK value, so
         * it already knows its own identifier. Every OTHER getter would trigger a SELECT.
         *
         * That is why this mapper is safe to run outside a transaction, while a mapper
         * reading getAuthor().getName() would throw LazyInitializationException (with
         * open-in-view disabled) or quietly cause N+1 (with it enabled).
         */
        public static BookResponse from(Book book) {
            return new BookResponse(
                    book.getId(), book.getTitle(), book.getIsbn(), book.getPrice(),
                    book.getPublishedOn(), book.getStock(),
                    book.getAuthor() == null ? null : book.getAuthor().getId(),
                    book.getVersion());
        }
    }

    public record CreateBookRequest(
            @NotNull Long authorId,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Pattern(regexp = "^[0-9-]{10,20}$", message = "isbn must be 10-20 digits/dashes")
            String isbn,
            @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal price,
            LocalDate publishedOn,
            @Min(0) int stock
    ) {}

    public record UpdatePriceRequest(
            @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal price
    ) {}

    public record PurchaseRequest(@Min(1) int quantity) {}

    /**
     * A stable wire shape for a page.
     *
     * INTERVIEW-adjacent, and a real Boot 3.3+ warning you will hit: serialising
     * Spring Data's PageImpl directly produces an unstable JSON structure and logs
     * "Serializing PageImpl instances as-is is not supported". Map to your own record
     * (or enable spring.data.web.pageable.serialization-mode=VIA_DTO). Your API contract
     * should not be an accident of a framework class's field names.
     */
    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
        public static <E, T> PageResponse<T> from(Page<E> page, java.util.function.Function<E, T> mapper) {
            return new PageResponse<>(
                    page.getContent().stream().map(mapper).toList(),
                    page.getNumber(), page.getSize(), page.getTotalElements(),
                    page.getTotalPages(), page.isFirst(), page.isLast());
        }
    }
}
