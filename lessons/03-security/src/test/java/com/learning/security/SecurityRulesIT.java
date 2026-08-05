package com.learning.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * URL rules and method security, driven with @WithMockUser.
 *
 * INTERVIEW: "How do you test secured endpoints?"
 * spring-security-test gives you:
 *   @WithMockUser(username, roles)  - populates the SecurityContext directly, skipping the
 *                                     authentication filters entirely. Ideal for testing
 *                                     AUTHORISATION rules without minting real tokens.
 *   @WithAnonymousUser              - explicitly unauthenticated
 *   @WithUserDetails("alice")       - loads the real user through your UserDetailsService
 *   SecurityMockMvcRequestPostProcessors.jwt() - simulates a decoded JWT with chosen claims
 *
 * Note `roles = "ADMIN"` produces the authority ROLE_ADMIN — the same prefix convention as
 * hasRole(). If you need a non-role authority use `authorities = "SCOPE_read"`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityRulesIT {

    @Autowired
    private MockMvc mockMvc;

    // ---------------------------------------------------------------------------------
    // URL RULES
    // ---------------------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    @DisplayName("permitAll: /api/public/** is reachable without authentication")
    void publicEndpointIsOpen() throws Exception {
        mockMvc.perform(get("/api/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("401 (not 403) when no credentials are supplied")
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    @DisplayName("403 (not 401) when authenticated but lacking the role")
    void authenticatedButWrongRoleGets403() throws Exception {
        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"USER", "ADMIN"})
    @DisplayName("hasRole('ADMIN') matches the authority ROLE_ADMIN")
    void adminReachesAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("admin"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("public documents are readable anonymously via the GET-only rule")
    void publicDocumentsReadableAnonymously() throws Exception {
        mockMvc.perform(get("/api/documents/public"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------
    // METHOD SECURITY
    // ---------------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    @DisplayName("@PreAuthorize(#username == authentication.name): own data allowed")
    void ownerCanListOwnDocuments() throws Exception {
        mockMvc.perform(get("/api/documents/owner/alice"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    @DisplayName("@PreAuthorize: another user's data denied, handled by the controller advice")
    void nonOwnerIsDenied() throws Exception {
        mockMvc.perform(get("/api/documents/owner/bob"))
                .andExpect(status().isForbidden())
                // Method-security denials are thrown INSIDE the dispatcher, so unlike
                // URL-rule denials they reach @ExceptionHandler in the controller.
                .andExpect(jsonPath("$.source").value("method-security (@PreAuthorize/@PostAuthorize)"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"USER", "ADMIN"})
    @DisplayName("@PreAuthorize: the `or hasRole('ADMIN')` branch lets admins through")
    void adminCanListAnyonesDocuments() throws Exception {
        mockMvc.perform(get("/api/documents/owner/bob"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "bob", roles = "USER")
    @DisplayName("@PostAuthorize rejects a document owned by someone else")
    void postAuthorizeBlocksOtherUsersDocument() throws Exception {
        mockMvc.perform(get("/api/documents/2"))     // owned by alice
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", roles = "USER")
    @DisplayName("@PreAuthorize with a guard bean blocks editing someone else's document")
    void guardBeanBlocksForeignEdit() throws Exception {
        mockMvc.perform(put("/api/documents/2")
                        .contentType("application/json")
                        .content("{\"content\":\"hacked\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", roles = "USER")
    @DisplayName("hasRole('ADMIN') on the service blocks a non-admin delete")
    void nonAdminCannotDelete() throws Exception {
        mockMvc.perform(delete("/api/documents/3"))
                .andExpect(status().isForbidden());
    }

    /**
     * Documents the deliberate vulnerability. The endpoint reaches a service method that
     * calls this.delete(id) internally, so @PreAuthorize("hasRole('ADMIN')") never runs and
     * a plain USER succeeds.
     *
     * Asserting the BUG rather than the fix is intentional: if someone later restructures
     * the service so the proxy is used, this test fails and forces a conversation.
     */
    @Test
    @WithMockUser(username = "bob", roles = "USER")
    @DisplayName("self-invocation bypasses @PreAuthorize — a non-admin CAN delete here")
    void selfInvocationBypassesMethodSecurity() throws Exception {
        mockMvc.perform(delete("/api/documents/3/unsafe-delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").exists());
    }

    // ---------------------------------------------------------------------------------
    // CSRF / CORS
    // ---------------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    @DisplayName("CSRF is disabled, so a state-changing request needs no CSRF token")
    void csrfDisabledForStatelessApi() throws Exception {
        // With CSRF enabled this POST would be 403 without .with(csrf()).
        mockMvc.perform(put("/api/documents/2")
                        .contentType("application/json")
                        .content("{\"content\":\"updated\"}"))
                .andExpect(status().isOk());
    }
}
