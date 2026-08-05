package com.learning.security.web;

import com.learning.security.entity.AppUser;
import com.learning.security.repository.AppUserRepository;
import com.learning.security.service.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthenticationManager authenticationManager,
                          TokenService tokenService,
                          AppUserRepository appUserRepository,
                          PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * The classic flow: authenticate the credentials once, then hand back a token the
     * client presents on every subsequent request.
     *
     * AuthenticationManager.authenticate() runs the DaoAuthenticationProvider, which loads
     * the UserDetails and calls passwordEncoder.matches(raw, storedHash). It throws on
     * failure — it never returns null or false.
     */
    @PostMapping("/login")
    public TokenService.IssuedToken login(@Valid @RequestBody LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        return tokenService.issue(authentication);
    }

    @PostMapping("/register")
    public TokenService.IssuedToken register(@Valid @RequestBody RegisterRequest request) {
        if (appUserRepository.existsByUsername(request.username())) {
            throw new IllegalStateException("username already taken");
        }
        // encode(), never store the raw value. The DelegatingPasswordEncoder writes
        // "{bcrypt}$2a$10$..." so the algorithm travels with the hash.
        var user = new AppUser(request.username(),
                passwordEncoder.encode(request.password()),
                Set.of("USER"));
        appUserRepository.save(user);

        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        return tokenService.issue(authentication);
    }

    /**
     * ONE handler for every authentication failure, returning ONE indistinguishable body.
     *
     * INTERVIEW: "Why not tell the user their account is locked?"
     * Because distinct responses for "no such user", "wrong password" and "locked" let an
     * attacker enumerate valid accounts and confirm which passwords are close. Log the
     * specific reason server-side; return the same 401 to the client every time.
     * (Timing differences leak the same information — that is why
     * DaoAuthenticationProvider still runs a dummy password check for unknown users.)
     */
    @ExceptionHandler({BadCredentialsException.class, DisabledException.class,
            LockedException.class, AuthenticationException.class})
    public ProblemDetail handleAuthFailure(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid username or password");
        problem.setTitle("Authentication failed");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflict(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Registration failed");
        return problem;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Size(min = 8, max = 100, message = "password must be at least 8 characters")
            String password) {}
}
