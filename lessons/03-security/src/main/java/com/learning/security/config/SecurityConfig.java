package com.learning.security.config;

import com.learning.security.web.SecurityProblemHandlers;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * INTERVIEW: "How is Spring Security wired into a Boot app?"
 *
 * A single servlet Filter named springSecurityFilterChain (registered as
 * DelegatingFilterProxy) sits in front of the DispatcherServlet. It delegates to
 * FilterChainProxy, which picks the FIRST matching SecurityFilterChain and runs its
 * ordered list of filters. Roughly:
 *
 *   SecurityContextHolderFilter   - restores the SecurityContext for this request
 *   CorsFilter / CsrfFilter
 *   BearerTokenAuthenticationFilter (resource server) or UsernamePasswordAuthenticationFilter
 *   ExceptionTranslationFilter    - turns AuthenticationException -> 401 (entry point)
 *                                   and AccessDeniedException  -> 403
 *   AuthorizationFilter           - LAST: evaluates authorizeHttpRequests rules
 *
 * Because AuthorizationFilter runs last, an exception thrown by an earlier filter never
 * reaches your @ControllerAdvice — which is why 401/403 bodies are configured HERE, on
 * the entry point and access-denied handler, not in a controller advice. That trips up a
 * lot of people ("why doesn't my exception handler catch 403?").
 *
 * Spring Security 6 notes worth stating:
 *   - WebSecurityConfigurerAdapter is GONE. You expose a SecurityFilterChain @Bean.
 *   - antMatchers()/mvcMatchers() are GONE, replaced by requestMatchers().
 *   - The lambda DSL is the only supported style; the old and() chaining is removed.
 */
@Configuration
@EnableMethodSecurity          // prePostEnabled=true by default in Spring Security 6
public class SecurityConfig {

    private final JwtProperties jwtProperties;

    public SecurityConfig(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * MULTIPLE FILTER CHAINS. FilterChainProxy picks the first chain whose securityMatcher
     * matches, and does NOT fall through to later chains. Ordering is therefore critical:
     * this @Order(1) chain claims /actuator/** so the main chain never sees those requests.
     *
     * This is the idiomatic way to give different URL spaces different rules — e.g. a
     * public API on JWT and an internal management port on basic auth.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .csrf(csrf -> csrf.disable())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiFilterChain(HttpSecurity http,
                                       JwtAuthenticationConverter jwtAuthenticationConverter,
                                       SecurityProblemHandlers problemHandlers) throws Exception {
        http
                /*
                 * INTERVIEW: "Why disable CSRF here — isn't that insecure?"
                 *
                 * CSRF attacks rely on the browser AUTOMATICALLY attaching credentials
                 * (a session cookie) to a cross-site request. A Bearer token is not sent
                 * automatically — the client must add the Authorization header explicitly —
                 * so there is nothing for a forged cross-site form to ride on.
                 *
                 * The moment you store the JWT in a COOKIE, that reasoning collapses and
                 * you need CSRF protection again. "We're stateless so CSRF is off" is only
                 * a correct answer when the token is not in a cookie.
                 */
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                /*
                 * STATELESS: no HttpSession is created and the SecurityContext is not
                 * persisted between requests. Every request must carry its own credential.
                 * This is what makes the service horizontally scalable with no sticky
                 * sessions and no session replication.
                 */
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Order matters: rules are evaluated top-down, FIRST MATCH WINS.
                        // A permitAll() placed above a stricter rule silently opens it up.
                        .requestMatchers("/api/auth/**", "/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/documents/public/**").permitAll()

                        /*
                         * PERMIT /error — and understand why, because it produces one of
                         * the most misleading bugs in Spring Security.
                         *
                         * When a controller throws an unhandled exception, the servlet
                         * container performs an internal ERROR dispatch to /error. That
                         * dispatch goes through the security filter chain again. If /error
                         * is not permitted, the real 500 is replaced by a 401 from the
                         * authentication entry point — so you get "401 Unauthorized" for a
                         * request that was perfectly well authenticated, and the actual
                         * exception is invisible in the response.
                         *
                         * The tell: the ProblemDetail's `instance` says "/error" instead of
                         * the endpoint you called. Always check the server log when that
                         * happens.
                         */
                        .requestMatchers("/error").permitAll()

                        // hasRole("ADMIN") tests for the authority "ROLE_ADMIN" — the
                        // prefix is added for you. hasAuthority("ADMIN") would NOT match.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/documents/**").authenticated()

                        // Deny-by-default. ALWAYS end with this: a missing rule should fail
                        // closed, not fall through to permitAll.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(problemHandlers.accessDeniedHandler()))

                // Also set on the chain itself, for failures raised outside the OAuth2 filter.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(problemHandlers.accessDeniedHandler()));

        return http.build();
    }

    /**
     * INTERVIEW: "401 vs 403?"
     *   401 Unauthorized - you are not authenticated (missing/invalid/expired token).
     *                      The name is a historical misnomer; it means unauthenticated.
     *   403 Forbidden    - you ARE authenticated, but you lack the required authority.
     * Returning 403 for an expired token, or 401 for a role failure, is a common bug.
     */

    /**
     * Maps JWT claims to Spring Security authorities.
     *
     * By DEFAULT the resource server reads the "scope"/"scp" claim and prefixes each value
     * with "SCOPE_". This app puts roles in a "roles" claim and wants the "ROLE_" prefix
     * so that hasRole("ADMIN") and @PreAuthorize("hasRole('ADMIN')") work — hence the
     * explicit converter. Forgetting this is why "my JWT has the role but access is denied".
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        // Principal name comes from the "sub" claim by default; being explicit is clearer.
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    private SecretKey secretKey() {
        return new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Verifies incoming tokens. */
    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /** Signs tokens in the login endpoint. */
    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    /**
     * INTERVIEW: "How should passwords be stored?"
     *
     * With a SLOW, salted, adaptive hash — bcrypt, scrypt or Argon2 — never SHA-256, and
     * never encryption (which is reversible). Speed is the enemy: a fast hash is a fast
     * offline cracking loop.
     *
     * createDelegatingPasswordEncoder() stores the algorithm as a prefix:
     *     {bcrypt}$2a$10$....
     * so you can migrate algorithms without invalidating existing passwords — new hashes
     * use the new default, old ones still verify with their recorded encoder. That answer
     * is a good deal better than just saying "BCryptPasswordEncoder".
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Explicit AuthenticationManager for the login endpoint.
     * DaoAuthenticationProvider loads the user via UserDetailsService and compares the
     * presented password with the stored hash using the PasswordEncoder.
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Without this, a wrong USERNAME and a wrong PASSWORD produce different exceptions,
        // letting an attacker enumerate valid accounts. Hide it.
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    /**
     * INTERVIEW: "CORS — what is actually happening?"
     *
     * The browser blocks cross-origin reads unless the SERVER opts in with
     * Access-Control-Allow-* headers. For anything beyond a "simple" request the browser
     * first sends an OPTIONS PREFLIGHT, which carries no credentials — so the preflight
     * must be permitted without authentication (Spring Security's CorsFilter runs before
     * the authorization filter and handles this for you).
     *
     * Note allowCredentials(true) is INCOMPATIBLE with allowedOrigins("*") — the spec
     * forbids the wildcard when credentials are allowed. Use allowedOriginPatterns, or
     * better, list your real origins as below.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);            // cache the preflight for an hour

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
