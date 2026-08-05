package com.learning.security.service;

import com.learning.security.repository.AppUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INTERVIEW: "How does Spring Security know about your users?"
 *
 * Through a UserDetailsService — a single method, loadUserByUsername, returning
 * UserDetails (username, password hash, authorities, and the four account status flags).
 * Defining this bean makes Boot back off from creating the default in-memory user with the
 * random generated password it prints at startup.
 *
 * ROLES vs AUTHORITIES — the distinction that catches people out:
 *   An "authority" is just a string. A "role" is an authority conventionally prefixed with
 *   "ROLE_". The helpers differ in whether they add that prefix for you:
 *     hasRole("ADMIN")        -> checks for authority "ROLE_ADMIN"    (prefix added)
 *     hasAuthority("ADMIN")   -> checks for authority "ADMIN"         (no prefix)
 *     .roles("ADMIN")         -> stores "ROLE_ADMIN"
 *     .authorities("ADMIN")   -> stores "ADMIN"
 *   Mixing them is the #1 cause of "my user has the role but gets 403". This app stores
 *   roles WITHOUT the prefix in the DB and adds it here, in exactly one place.
 */
@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public JpaUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user = appUserRepository.findByUsername(username)
                // The message must not distinguish "no such user" from "wrong password".
                // DaoAuthenticationProvider.hideUserNotFoundExceptions handles the response,
                // but do not help an attacker by logging a distinguishing message either.
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRoles().toArray(String[]::new))   // adds the ROLE_ prefix
                .disabled(!user.isEnabled())
                .accountLocked(user.isAccountLocked())
                .build();
    }
}
