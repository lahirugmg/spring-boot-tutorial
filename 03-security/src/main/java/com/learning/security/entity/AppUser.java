package com.learning.security.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_user_seq_gen")
    @SequenceGenerator(name = "app_user_seq_gen", sequenceName = "app_user_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    /**
     * Stores the HASH, never the password. Named `passwordHash` on purpose — a field called
     * `password` invites someone to log it or return it in a DTO.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "account_locked", nullable = false)
    private boolean accountLocked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * EAGER here is a deliberate exception to the "always LAZY" rule from module 02.
     * Authorities are needed on every single authenticated request, and the UserDetails is
     * built outside any long-lived persistence context — a LAZY collection would throw
     * LazyInitializationException in the authentication filter.
     *
     * Roles are stored WITHOUT the "ROLE_" prefix ("USER", "ADMIN"); the prefix is added
     * when building authorities. See JpaUserDetailsService for why that distinction matters.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 50)
    private Set<String> roles = new LinkedHashSet<>();

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, Set<String> roles) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.roles = new LinkedHashSet<>(roles);
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAccountLocked() { return accountLocked; }
    public void setAccountLocked(boolean accountLocked) { this.accountLocked = accountLocked; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<String> getRoles() { return roles; }

    @Override
    public String toString() {
        // Never let the hash reach a log line.
        return "AppUser{id=%d, username='%s', roles=%s}".formatted(id, username, roles);
    }
}
