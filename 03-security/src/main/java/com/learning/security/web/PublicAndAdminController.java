package com.learning.security.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Three endpoints that make the URL-rule tiers visible:
 *   /api/public/ping   permitAll        -> 200 with no token
 *   /api/me            authenticated    -> 401 without a token
 *   /api/admin/stats   hasRole('ADMIN') -> 403 for a plain USER
 */
@RestController
public class PublicAndAdminController {

    @GetMapping("/api/public/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "authenticated", false,
                "note", "matched by .requestMatchers(\"/api/public/**\").permitAll()");
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication authentication) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", authentication.getName());
        result.put("authorities", authentication.getAuthorities().stream().map(Object::toString).toList());
        result.put("authenticationType", authentication.getClass().getSimpleName());
        result.put("principalType", authentication.getPrincipal().getClass().getSimpleName());

        // Same object, fetched the static way — proves the ThreadLocal holds it too.
        var fromHolder = SecurityContextHolder.getContext().getAuthentication();
        result.put("sameAsSecurityContextHolder", fromHolder == authentication);
        return result;
    }

    @GetMapping("/api/admin/stats")
    public Map<String, Object> adminStats(Authentication authentication) {
        return Map.of(
                "caller", authentication.getName(),
                "note", "reached only because the URL rule required hasRole('ADMIN')",
                "reminder", List.of(
                        "hasRole('ADMIN') checks the authority 'ROLE_ADMIN'",
                        "hasAuthority('ADMIN') would NOT match it"));
    }
}
