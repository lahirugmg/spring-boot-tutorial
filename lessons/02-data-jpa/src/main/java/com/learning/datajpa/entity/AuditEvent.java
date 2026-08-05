package com.learning.datajpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Written by {@code InventoryService} using {@code Propagation.REQUIRES_NEW} so the audit
 * row SURVIVES a rollback of the surrounding business transaction. That behaviour is the
 * whole point of this entity — see InventoryService for the walkthrough.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_seq_gen")
    @SequenceGenerator(name = "audit_seq_gen", sequenceName = "audit_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected AuditEvent() {
    }

    public AuditEvent(String action, String detail) {
        this.action = action;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
