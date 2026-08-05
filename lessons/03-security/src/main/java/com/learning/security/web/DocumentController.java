package com.learning.security.web;

import com.learning.security.entity.Document;
import com.learning.security.service.DocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** Open to everyone — permitted by the URL rule in SecurityConfig. */
    @GetMapping("/public")
    public List<DocumentResponse> publicDocuments() {
        return documentService.findPublicDocuments().stream().map(DocumentResponse::from).toList();
    }

    /**
     * INTERVIEW: "How do you get the current user in a controller?"
     *
     * Four ways, best first:
     *   1. an Authentication method parameter (below) — resolved by Spring, trivially
     *      testable because you can pass one in
     *   2. @AuthenticationPrincipal Jwt jwt — gives the token itself, so you can read
     *      custom claims
     *   3. @CurrentSecurityContext(expression = "authentication.name") String username
     *   4. SecurityContextHolder.getContext().getAuthentication() — a static call, works
     *      anywhere including services, but it is hidden global state and awkward in tests
     *
     * SecurityContextHolder is backed by a ThreadLocal by default. That matters: spawn a
     * plain thread or hand work to an @Async executor and the context is GONE. Fixes:
     * DelegatingSecurityContextExecutor, or MODE_INHERITABLETHREADLOCAL (which does not
     * help with pooled threads).
     */
    @GetMapping("/mine")
    public Map<String, Object> myDocuments(Authentication authentication,
                                           @AuthenticationPrincipal Jwt jwt) {
        var result = new LinkedHashMap<String, Object>();
        result.put("username", authentication.getName());
        result.put("authorities", authentication.getAuthorities().stream()
                .map(Object::toString).toList());
        result.put("tokenIssuer", jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
        result.put("tokenExpiresAt", jwt.getExpiresAt());
        result.put("rolesClaim", jwt.getClaimAsStringList("roles"));
        result.put("documents", documentService.findByOwner(authentication.getName())
                .stream().map(DocumentResponse::from).toList());
        return result;
    }

    /** @PreAuthorize("#username == authentication.name or hasRole('ADMIN')") lives on the service. */
    @GetMapping("/owner/{username}")
    public List<DocumentResponse> byOwner(@PathVariable String username) {
        return documentService.findByOwner(username).stream().map(DocumentResponse::from).toList();
    }

    /** @PostAuthorize — the row is loaded, then the ownership check runs. */
    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable long id) {
        return DocumentResponse.from(documentService.getById(id));
    }

    /** @PreAuthorize("@documentGuard.canEdit(#id, authentication)") */
    @PutMapping("/{id}")
    public DocumentResponse update(@PathVariable long id, @Valid @RequestBody UpdateRequest request) {
        return DocumentResponse.from(documentService.updateContent(id, request.content()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse create(@Valid @RequestBody CreateRequest request,
                                   Authentication authentication) {
        return DocumentResponse.from(documentService.create(
                request.title(), authentication.getName(), request.classification(), request.content()));
    }

    /** @PreAuthorize("hasRole('ADMIN')") on the service — a non-admin gets 403. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        documentService.delete(id);
    }

    /**
     * THE DELIBERATE HOLE. Calls a service method that internally does this.delete(id),
     * so the @PreAuthorize("hasRole('ADMIN')") is bypassed entirely by self-invocation.
     * Any authenticated user can delete. Compare with the endpoint above.
     */
    @DeleteMapping("/{id}/unsafe-delete")
    public Map<String, Object> unsafeDelete(@PathVariable long id) {
        documentService.deleteBypassingSecurity(id);
        return Map.of(
                "deleted", id,
                "warning", "@PreAuthorize was NOT evaluated — self-invocation bypassed the proxy",
                "lesson", "method security is AOP; internal `this.` calls are not intercepted");
    }

    /**
     * AccessDeniedException from METHOD security is thrown inside the DispatcherServlet,
     * so unlike a URL-rule denial it CAN be handled here. URL-rule denials are handled by
     * the AccessDeniedHandler in SecurityProblemHandlers instead.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "You do not have permission to perform this action on this resource.");
        problem.setTitle("Access denied");
        problem.setProperty("source", "method-security (@PreAuthorize/@PostAuthorize)");
        return problem;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Document not found");
        return problem;
    }

    public record DocumentResponse(Long id, String title, String ownerUsername,
                                   String classification, String content) {
        static DocumentResponse from(Document document) {
            return new DocumentResponse(document.getId(), document.getTitle(),
                    document.getOwnerUsername(), document.getClassification(), document.getContent());
        }
    }

    public record CreateRequest(@NotBlank String title, @NotBlank String classification, String content) {}

    public record UpdateRequest(@NotBlank String content) {}
}
