package com.learning.security.service;

import com.learning.security.repository.DocumentRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * A named bean referenced from SpEL as {@code @documentGuard.canEdit(#id, authentication)}.
 *
 * Why this pattern is worth knowing: it keeps authorisation logic in ordinary Java where
 * it can be unit-tested and stepped through in a debugger, instead of in an unverifiable
 * expression string. Spring Security's own docs recommend it for anything non-trivial.
 *
 * The bean name comes from the class name with a lowercase first letter — `documentGuard`.
 */
@Component("documentGuard")
public class DocumentGuard {

    private final DocumentRepository documentRepository;

    public DocumentGuard(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public boolean canEdit(long documentId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (isAdmin) {
            return true;
        }
        return documentRepository.findById(documentId)
                .map(document -> document.getOwnerUsername().equals(authentication.getName()))
                // Fail CLOSED: an unknown id denies rather than permits. Returning true for
                // "not found" here would be an authorisation bypass on a race.
                .orElse(false);
    }
}
