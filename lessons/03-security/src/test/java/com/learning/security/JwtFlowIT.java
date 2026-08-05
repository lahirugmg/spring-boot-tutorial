package com.learning.security;

import com.learning.security.config.JwtProperties;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end token flow over real HTTP: log in, receive a signed JWT, present it as a
 * Bearer token, and confirm the resource server accepts or rejects it correctly.
 *
 * Unlike SecurityRulesIT (which uses @WithMockUser and skips the filters), this exercises
 * the actual BearerTokenAuthenticationFilter, NimbusJwtDecoder and the claim-to-authority
 * conversion — the parts @WithMockUser deliberately bypasses. You want both kinds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class JwtFlowIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    private String login(String username, String password) {
        var body = Map.of("username", username, "password", password);
        var response = restTemplate.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), JSON);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return (String) response.getBody().get("accessToken");
    }

    private static HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    @DisplayName("login returns a signed JWT whose claims carry the roles")
    void loginIssuesToken() {
        String token = login("alice", "password123");

        assertThat(token).isNotBlank();
        // header.payload.signature
        assertThat(token.split("\\.")).hasSize(3);

        var response = restTemplate.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("name", "alice");
        assertThat(response.getBody().get("authorities")).isEqualTo(List.of("ROLE_USER"));
        // Proves the JwtGrantedAuthoritiesConverter mapped "roles":["USER"] -> ROLE_USER.
        assertThat(response.getBody()).containsEntry("principalType", "Jwt");
    }

    @Test
    @DisplayName("wrong password returns an indistinguishable 401")
    void wrongPasswordIsRejected() {
        var body = Map.of("username", "alice", "password", "not-the-password");
        var response = restTemplate.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("detail", "Invalid username or password");
    }

    @Test
    @DisplayName("an unknown user gets the SAME message as a wrong password (no enumeration)")
    void unknownUserIsIndistinguishable() {
        var body = Map.of("username", "does-not-exist", "password", "whatever12");
        var response = restTemplate.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("detail", "Invalid username or password");
    }

    @Test
    @DisplayName("a locked account cannot log in, and is not told why")
    void lockedAccountCannotLogIn() {
        var body = Map.of("username", "locked", "password", "password123");
        var response = restTemplate.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("detail", "Invalid username or password");
    }

    @Test
    @DisplayName("a garbage token is rejected with 401")
    void malformedTokenRejected() {
        var response = restTemplate.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(bearer("not.a.jwt")), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Forges a correctly SIGNED token whose exp is in the past. This proves the decoder
     * validates the `exp` claim and does not merely check the signature — a distinction
     * that matters, because a valid signature on an expired token is exactly what an
     * attacker replaying an old token has.
     */
    @Test
    @DisplayName("an expired but correctly signed token is rejected with 401")
    void expiredTokenRejected() {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        var claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(past)
                .expiresAt(past.plus(1, ChronoUnit.MINUTES))    // expired an hour ago
                .subject("alice")
                .claim("roles", List.of("USER"))
                .build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        var response = restTemplate.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(bearer(expired)), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A token signed with the WRONG key. This is the "anyone can forge a JWT if they only
     * base64-decode and re-encode it" myth, disproved: tamper with the payload and the
     * signature no longer verifies.
     */
    @Test
    @DisplayName("a token signed with a different key fails signature verification")
    void tokenSignedWithWrongKeyRejected() {
        String valid = login("alice", "password123");
        String[] parts = valid.split("\\.");
        // Keep header and payload, corrupt the signature.
        String tampered = parts[0] + "." + parts[1] + ".Zm9yZ2VkLXNpZ25hdHVyZQ";

        var response = restTemplate.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(bearer(tampered)), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("admin's token carries both roles and reaches the admin endpoint")
    void adminTokenCarriesBothRoles() {
        String token = login("admin", "password123");

        var me = restTemplate.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), JSON);
        assertThat(me.getBody()).isNotNull();
        assertThat(me.getBody().get("authorities"))
                .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");

        var admin = restTemplate.exchange("/api/admin/stats", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), JSON);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("registering a new user works and immediately returns a usable token")
    void registrationIssuesUsableToken() {
        var body = Map.of("username", "charlie-" + System.nanoTime(), "password", "password123");
        var response = restTemplate.exchange("/api/auth/register", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("roles")).isEqualTo(List.of("USER"));

        String token = (String) response.getBody().get("accessToken");
        var me = restTemplate.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), JSON);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
