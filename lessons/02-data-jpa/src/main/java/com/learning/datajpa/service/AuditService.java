package com.learning.datajpa.service;

import com.learning.datajpa.entity.AuditEvent;
import com.learning.datajpa.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exists as a SEPARATE BEAN for one reason: propagation only works across a proxy
 * boundary. Put {@code recordInNewTransaction} on InventoryService itself and calling it
 * with {@code this.} would silently join the caller's transaction — the REQUIRES_NEW would
 * be ignored and the audit row would roll back with everything else.
 *
 * That is the single most useful thing to remember about propagation in practice.
 */
@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * INTERVIEW: "Name the propagation levels and when you'd use each."
     *
     *   REQUIRED (default) - join the caller's transaction, or start one if none exists.
     *                        99% of your code. Note: because it JOINS, an exception thrown
     *                        anywhere inside marks the WHOLE transaction rollback-only —
     *                        catching it in the caller does not save you, you just get
     *                        UnexpectedRollbackException at commit.
     *   REQUIRES_NEW       - always suspend the caller and start an independent physical
     *                        transaction. Commits/rolls back on its own. Use for audit
     *                        trails and outbox writes that must survive a failure.
     *                        COSTS A SECOND CONNECTION from the pool while the outer one
     *                        is suspended — a pool of 10 with nested REQUIRES_NEW can
     *                        deadlock itself under load. Real production incident material.
     *   NESTED             - a JDBC SAVEPOINT inside the current transaction. Rolls back to
     *                        the savepoint only. JDBC-only; not supported by JTA.
     *   SUPPORTS           - join if one exists, otherwise run non-transactionally.
     *   NOT_SUPPORTED      - suspend any transaction and run without one.
     *   MANDATORY          - throw if there is no existing transaction (enforces a contract).
     *   NEVER              - throw if there IS one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(String action, String detail) {
        auditEventRepository.save(new AuditEvent(action, truncate(detail)));
    }

    /** Same write, but joining the caller — so it rolls back with the caller. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordInSameTransaction(String action, String detail) {
        auditEventRepository.save(new AuditEvent(action, truncate(detail)));
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
