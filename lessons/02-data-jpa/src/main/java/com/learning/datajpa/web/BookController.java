package com.learning.datajpa.web;

import com.learning.datajpa.projection.BookSummary;
import com.learning.datajpa.projection.BookWithAuthorDto;
import com.learning.datajpa.service.CatalogService;
import com.learning.datajpa.service.InventoryService;
import com.learning.datajpa.web.dto.BookDtos.BookResponse;
import com.learning.datajpa.web.dto.BookDtos.CreateBookRequest;
import com.learning.datajpa.web.dto.BookDtos.PageResponse;
import com.learning.datajpa.web.dto.BookDtos.PurchaseRequest;
import com.learning.datajpa.web.dto.BookDtos.UpdatePriceRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/books")
public class BookController {

    private final CatalogService catalogService;
    private final InventoryService inventoryService;

    public BookController(CatalogService catalogService, InventoryService inventoryService) {
        this.catalogService = catalogService;
        this.inventoryService = inventoryService;
    }

    /**
     * Every filter is optional and composed into a Specification.
     *
     * @PageableDefault supplies defaults when the client omits them. Spring Data's
     * PageableHandlerMethodArgumentResolver reads ?page=&size=&sort=field,dir automatically.
     * Always cap the page size (`size` is capped at 2000 by default) — an unbounded
     * ?size=1000000 is a trivial denial-of-service against your own database.
     */
    @GetMapping
    public PageResponse<BookResponse> search(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String country,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publishedAfter,
            @RequestParam(defaultValue = "false") boolean onlyInStock,
            @PageableDefault(size = 10, sort = "title", direction = Sort.Direction.ASC) Pageable pageable) {

        var page = catalogService.search(title, maxPrice, country, publishedAfter, onlyInStock, pageable);
        return PageResponse.from(page, BookResponse::from);
    }

    /** DTO projection: one query, author name included, no entities involved. */
    @GetMapping("/dto")
    public List<BookWithAuthorDto> listAsDto() {
        return catalogService.listAsDto();
    }

    /** Interface projection, scoped to one author. */
    @GetMapping("/summaries")
    public List<BookSummary> summaries(@RequestParam long authorId) {
        return catalogService.summariesForAuthor(authorId);
    }

    @GetMapping("/{id}")
    public BookResponse get(@PathVariable long id) {
        return BookResponse.from(catalogService.get(id));
    }

    @PostMapping
    public ResponseEntity<BookResponse> create(@Valid @RequestBody CreateBookRequest request,
                                               UriComponentsBuilder uriBuilder) {
        var book = catalogService.addBook(request.authorId(), request.title(), request.isbn(),
                request.price(), request.publishedOn(), request.stock());
        var location = uriBuilder.path("/api/books/{id}").buildAndExpand(book.getId()).toUri();
        return ResponseEntity.created(location).body(BookResponse.from(book));
    }

    @PatchMapping("/{id}/price")
    public BookResponse updatePrice(@PathVariable long id, @Valid @RequestBody UpdatePriceRequest request) {
        return BookResponse.from(catalogService.updatePrice(id, request.price()));
    }

    /** Rolls back on failure, but the audit row survives (REQUIRES_NEW). */
    @PostMapping("/{id}/purchase")
    public BookResponse purchase(@PathVariable long id, @Valid @RequestBody PurchaseRequest request) {
        return BookResponse.from(inventoryService.purchase(id, request.quantity()));
    }

    /** Same, but SELECT ... FOR UPDATE holds a row lock for the transaction. */
    @PostMapping("/{id}/purchase-locked")
    public BookResponse purchaseLocked(@PathVariable long id, @Valid @RequestBody PurchaseRequest request) {
        return BookResponse.from(inventoryService.purchaseWithPessimisticLock(id, request.quantity()));
    }
}
