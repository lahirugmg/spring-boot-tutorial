package com.learning.datajpa.web;

import com.learning.datajpa.entity.AuditEvent;
import com.learning.datajpa.repository.AuditEventRepository;
import com.learning.datajpa.service.CatalogService;
import com.learning.datajpa.service.FetchStrategyService;
import com.learning.datajpa.service.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints whose only job is to make an invisible behaviour visible. Each maps to a
 * question you will be asked; hit it, read the numbers, then explain them out loud.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final FetchStrategyService fetchStrategyService;
    private final InventoryService inventoryService;
    private final CatalogService catalogService;
    private final AuditEventRepository auditEventRepository;

    public DemoController(FetchStrategyService fetchStrategyService,
                          InventoryService inventoryService,
                          CatalogService catalogService,
                          AuditEventRepository auditEventRepository) {
        this.fetchStrategyService = fetchStrategyService;
        this.inventoryService = inventoryService;
        this.catalogService = catalogService;
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * The N+1 comparison table. Each row is the same logical read with a different fetch
     * plan, plus the number of JDBC statements it actually cost.
     */
    @GetMapping("/fetch-strategies")
    public List<FetchStrategyService.StrategyResult> fetchStrategies() {
        return fetchStrategyService.runAll();
    }

    /**
     * Proves the self-invocation trap.
     *   viaProxy         -> true : the controller calls through the Spring proxy
     *   viaSelfInvocation-> false: the bean called its own @Transactional method with `this`
     */
    @GetMapping("/self-invocation")
    public Map<String, Object> selfInvocation() {
        var result = new LinkedHashMap<String, Object>();
        result.put("viaProxy", inventoryService.transactionalProbe());
        result.put("viaSelfInvocation", inventoryService.selfInvocationTrap());
        result.put("explanation", "@Transactional is proxy-based; `this.method()` skips the interceptor");
        return result;
    }

    /**
     * LazyInitializationException, on demand.
     *
     * getDetached() returns an entity whose transaction has already committed, so the
     * persistence context is gone and book.getAuthor() is an uninitialised proxy. Reading
     * any property other than the id blows up — with spring.jpa.open-in-view=false, which
     * is set deliberately in application.yml.
     */
    @GetMapping("/lazy-initialization/{bookId}")
    public Map<String, Object> lazyInitialization(@PathVariable long bookId) {
        var result = new LinkedHashMap<String, Object>();
        var book = catalogService.getDetached(bookId);

        result.put("title", book.getTitle());
        // Safe: a proxy always knows its own identifier, so this costs no query.
        result.put("authorIdFromProxy", book.getAuthor().getId());

        try {
            // Not safe: any other property forces initialisation, and the session is closed.
            result.put("authorName", book.getAuthor().getName());
            result.put("outcome", "no exception — open-in-view must be ON");
        } catch (org.hibernate.LazyInitializationException ex) {
            result.put("authorName", null);
            result.put("outcome", "LazyInitializationException: " + ex.getMessage());
            result.put("fix", "fetch it in the query (join fetch / @EntityGraph) or map to a DTO inside the transaction");
        }
        return result;
    }

    /**
     * REQUIRES_NEW vs REQUIRED, side by side.
     *
     * Both calls fail with InsufficientStockException, so both purchases roll back.
     * The difference is what is left in audit_event afterwards.
     */
    @PostMapping("/propagation/{bookId}")
    public Map<String, Object> propagation(@PathVariable long bookId) {
        var result = new LinkedHashMap<String, Object>();
        long before = auditEventRepository.count();

        // Deliberately impossible quantity -> guaranteed rollback of the business transaction.
        result.put("requiresNew", attemptAndDescribe(
                () -> inventoryService.purchase(bookId, 999_999)));
        long afterRequiresNew = auditEventRepository.count();

        result.put("required", attemptAndDescribe(
                () -> inventoryService.purchaseWithJoinedAudit(bookId, 999_999)));
        long afterRequired = auditEventRepository.count();

        result.put("auditRowsBefore", before);
        result.put("auditRowsAfterRequiresNew", afterRequiresNew);
        result.put("auditRowsAfterRequired", afterRequired);
        result.put("verdict", """
                REQUIRES_NEW committed its audit row independently (+%d), \
                REQUIRED rolled back with the caller (+%d)."""
                .formatted(afterRequiresNew - before, afterRequired - afterRequiresNew));
        return result;
    }

    /**
     * The checked-exception rollback rule.
     *   default        -> a checked exception COMMITS the mutation (stock +100 persists)
     *   rollbackFor    -> the same checked exception rolls it back
     */
    @PostMapping("/rollback-rules/{bookId}")
    public Map<String, Object> rollbackRules(@PathVariable long bookId) {
        var result = new LinkedHashMap<String, Object>();

        // Case A: plain @Transactional. Both methods add 100 to stock and then throw the
        // SAME checked exception; only the rollback RULE differs.
        int beforeDefault = catalogService.get(bookId).getStock();
        try {
            inventoryService.mutateThenThrowChecked(bookId);
        } catch (InventoryService.BusinessException expected) {
            // swallowed on purpose — we care about what the DB kept, not the exception
        }
        int afterDefault = catalogService.get(bookId).getStock();

        // Case B: @Transactional(rollbackFor = BusinessException.class)
        int beforeRollbackFor = afterDefault;
        try {
            inventoryService.mutateThenThrowCheckedWithRollbackFor(bookId);
        } catch (InventoryService.BusinessException expected) {
            // swallowed on purpose
        }
        int afterRollbackFor = catalogService.get(bookId).getStock();

        int deltaDefault = afterDefault - beforeDefault;
        int deltaRollbackFor = afterRollbackFor - beforeRollbackFor;

        result.put("caseA_default", Map.of(
                "annotation", "@Transactional",
                "stockBefore", beforeDefault,
                "stockAfter", afterDefault,
                "delta", deltaDefault,
                "outcome", deltaDefault == 0 ? "ROLLED BACK" : "COMMITTED"));

        result.put("caseB_rollbackFor", Map.of(
                "annotation", "@Transactional(rollbackFor = BusinessException.class)",
                "stockBefore", beforeRollbackFor,
                "stockAfter", afterRollbackFor,
                "delta", deltaRollbackFor,
                "outcome", deltaRollbackFor == 0 ? "ROLLED BACK" : "COMMITTED"));

        result.put("verdict", """
                The default rollback rule is RuntimeException and Error ONLY. \
                A checked exception COMMITS the work done before it was thrown (delta %+d), \
                unless you declare rollbackFor (delta %+d)."""
                .formatted(deltaDefault, deltaRollbackFor));
        result.put("note", "case A permanently added stock — that is the point, it really committed");
        return result;
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit() {
        return auditEventRepository.findAll();
    }

    private static String attemptAndDescribe(Runnable action) {
        try {
            action.run();
            return "unexpectedly succeeded";
        } catch (RuntimeException ex) {
            return "rolled back with " + ex.getClass().getSimpleName();
        }
    }
}
