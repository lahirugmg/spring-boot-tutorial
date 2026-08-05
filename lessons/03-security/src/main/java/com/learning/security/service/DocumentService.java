package com.learning.security.service;

import com.learning.security.entity.Document;
import com.learning.security.repository.DocumentRepository;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * METHOD SECURITY, which is where the interesting authorisation questions live.
 *
 * INTERVIEW: "URL-based rules vs method security — when do you use which?"
 *   URL rules (authorizeHttpRequests) are coarse: they see only the request, not the data.
 *   Perfect for "/api/admin/** requires ADMIN".
 *   Method security sees the ARGUMENTS and the RETURN VALUE, so it can express
 *   "only the owner of this document" — which no URL pattern can.
 *   Real systems use both: URL rules as a blunt outer gate, method security for the
 *   data-dependent decisions.
 *
 * The annotations:
 *   @PreAuthorize  - evaluated BEFORE the call. Can reference arguments with #paramName.
 *   @PostAuthorize - evaluated AFTER, can reference `returnObject`. WARNING: the method
 *                    has already run. Never use it on something with side effects, and
 *                    beware that it loads data the caller may not be allowed to see.
 *   @PreFilter / @PostFilter - filter collection arguments/returns in place. Convenient,
 *                    but they filter in MEMORY — for a large result set you want the
 *                    predicate pushed into the query instead.
 *   @Secured / @RolesAllowed - role checks only, no SpEL. Legacy; prefer @PreAuthorize.
 *
 * AND THE BIG CAVEAT: method security is implemented with the same AOP proxies as
 * @Transactional, so it has the SAME self-invocation blind spot. An internal
 * this.deleteDocument(...) call performs NO authorisation check. Module 02 proves the
 * mechanism; the consequence here is a security hole rather than a missing transaction.
 */
@Service
public class DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public List<Document> findPublicDocuments() {
        return documentRepository.findByClassification("PUBLIC");
    }

    /**
     * Argument-based rule. `#username` binds the method parameter; `authentication` is the
     * current Authentication. So a user may list only their OWN documents — unless they
     * are an admin.
     */
    @PreAuthorize("#username == authentication.name or hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<Document> findByOwner(String username) {
        return documentRepository.findByOwnerUsername(username);
    }

    /**
     * Return-value based rule. The document is fetched first, then the check runs.
     * Use sparingly and never where merely loading the row is itself sensitive.
     */
    @PostAuthorize("returnObject.ownerUsername == authentication.name or hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Document getById(long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document " + id + " not found"));
    }

    /**
     * Delegating to a BEAN is the cleanest way to express a non-trivial rule. `@documentGuard`
     * resolves the Spring bean by name and calls a normal Java method, so the logic is
     * testable, debuggable and not trapped in a string. Strongly preferable to cramming
     * boolean algebra into SpEL.
     */
    @PreAuthorize("@documentGuard.canEdit(#id, authentication)")
    @Transactional
    public Document updateContent(long id, String content) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document " + id + " not found"));
        document.setContent(content);
        return document;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void delete(long id) {
        documentRepository.deleteById(id);
    }

    /**
     * THE VULNERABILITY, on purpose. This method is not annotated, and it calls
     * this.delete(id) internally — a self-invocation, so the @PreAuthorize on delete() is
     * NEVER EVALUATED. Any authenticated user reaching this method deletes the document.
     *
     * Exposed at DELETE /api/documents/{id}/unsafe-delete so you can demonstrate it.
     */
    @Transactional
    public void deleteBypassingSecurity(long id) {
        this.delete(id);
    }

    @Transactional
    public Document create(String title, String owner, String classification, String content) {
        return documentRepository.save(new Document(title, owner, classification, content));
    }
}
