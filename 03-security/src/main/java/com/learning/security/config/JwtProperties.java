package com.learning.security.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(

        /**
         * HMAC signing key. HS256 requires at least 256 bits (32 ASCII chars) — the
         * @Size constraint makes a too-short key a STARTUP failure rather than a runtime
         * one, which is exactly the sort of fail-fast config validation interviewers like.
         *
         * In production this comes from a secret manager, never from a checked-in file.
         * A symmetric key also means every service that can VERIFY a token can also FORGE
         * one; that is the argument for asymmetric RS256/ES256 with a published JWKS.
         */
        @NotBlank @Size(min = 32, message = "HS256 needs a key of at least 256 bits (32 chars)")
        String secret,

        @DefaultValue("PT15M") Duration accessTokenTtl,

        @DefaultValue("spring-boot-interview-prep") String issuer
) {}
