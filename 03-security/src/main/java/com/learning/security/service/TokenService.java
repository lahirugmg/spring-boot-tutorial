package com.learning.security.service;

import com.learning.security.config.JwtProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Issues the access token.
 *
 * INTERVIEW: "What's actually in a JWT, and what makes it secure?"
 *
 * Three base64url segments: header.payload.signature.
 *   header    - {"alg":"HS256","typ":"JWT"}
 *   payload   - the claims. Registered ones: iss, sub, aud, exp, nbf, iat, jti.
 *   signature - HMAC (or RSA/ECDSA) over the first two segments.
 *
 * THE POINT PEOPLE GET WRONG: a JWT is SIGNED, not ENCRYPTED. The payload is merely
 * base64-encoded and anyone holding the token can read it — paste one into jwt.io.
 * Never put PII, permissions you would not show the user, or secrets in the claims.
 *
 * Follow-ups worth being ready for:
 *   "How do you revoke one?"  You can't, not really — that is the trade-off for
 *                             statelessness. Mitigate with short TTLs plus a refresh
 *                             token, or keep a denylist of `jti` values (which reintroduces
 *                             the shared state JWTs were meant to avoid).
 *   "alg: none attack"        A client-supplied header claiming alg=none, or swapping RS256
 *                             for HS256 and signing with the public key. Nimbus/Spring
 *                             pins the expected algorithm (see NimbusJwtDecoder
 *                             .macAlgorithm(HS256)), which is why this matters.
 *   "Where do you store it?"  localStorage is XSS-readable; an httpOnly cookie is not, but
 *                             brings CSRF back. There is no free option.
 */
@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public IssuedToken issue(Authentication authentication) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        // Strip the ROLE_ prefix for the wire format; JwtAuthenticationConverter puts it
        // back when the token is validated. Keeping the claim prefix-free means the token
        // is not coupled to a Spring Security naming convention.
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(authentication.getName())
                .claim("roles", roles)
                .build();

        /*
         * The JwsHeader is REQUIRED here, and omitting it is a genuinely confusing failure.
         *
         * JwtEncoderParameters.from(claims) with no header makes NimbusJwtEncoder default
         * to RS256. With an ImmutableSecret (a symmetric HMAC key) there is no RSA key to
         * find, so it fails with:
         *
         *     JwtEncodingException: An error occurred while attempting to encode the Jwt:
         *     Failed to select a JWK signing key
         *
         * — which says nothing about algorithm mismatch. State HS256 explicitly and it
         * matches the NimbusJwtDecoder.macAlgorithm(HS256) on the verifying side.
         */
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, "Bearer", properties.accessTokenTtl().toSeconds(), roles);
    }

    public record IssuedToken(String accessToken, String tokenType, long expiresInSeconds, List<String> roles) {}
}
