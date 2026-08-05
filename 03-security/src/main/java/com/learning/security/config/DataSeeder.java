package com.learning.security.config;

import com.learning.security.entity.AppUser;
import com.learning.security.entity.Document;
import com.learning.security.repository.AppUserRepository;
import com.learning.security.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

/**
 * Seeds demo users at startup rather than in a Flyway migration, because the password
 * hashes must be produced by the SAME PasswordEncoder the app authenticates with.
 * Hardcoding a hash in SQL works until someone changes the encoder, and then every login
 * fails for reasons nobody can find.
 *
 * Idempotent: it only writes when the table is empty, so restarts are safe.
 *
 * All demo users share the password `password123`. Obviously never do this anywhere real.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String DEMO_PASSWORD = "password123";

    @Bean
    ApplicationRunner seedUsersAndDocuments(AppUserRepository users,
                                            DocumentRepository documents,
                                            PasswordEncoder passwordEncoder) {
        return args -> {
            if (users.count() == 0) {
                users.save(new AppUser("alice", passwordEncoder.encode(DEMO_PASSWORD), Set.of("USER")));
                users.save(new AppUser("bob", passwordEncoder.encode(DEMO_PASSWORD), Set.of("USER")));
                users.save(new AppUser("admin", passwordEncoder.encode(DEMO_PASSWORD), Set.of("USER", "ADMIN")));

                var locked = new AppUser("locked", passwordEncoder.encode(DEMO_PASSWORD), Set.of("USER"));
                locked.setAccountLocked(true);
                users.save(locked);

                log.info("Seeded users: alice/USER, bob/USER, admin/USER+ADMIN, locked/USER (locked). "
                        + "Password for all: {}", DEMO_PASSWORD);
                log.info("Stored hash format (note the {{id}} prefix from DelegatingPasswordEncoder): {}",
                        users.findByUsername("alice").orElseThrow().getPasswordHash());
            }

            if (documents.count() == 0) {
                documents.save(new Document("Public roadmap", "admin", "PUBLIC",
                        "Anyone may read this."));
                documents.save(new Document("Alice's notes", "alice", "PRIVATE",
                        "Only alice (or an admin) may read this."));
                documents.save(new Document("Alice's draft", "alice", "PRIVATE",
                        "Second document owned by alice."));
                documents.save(new Document("Bob's notes", "bob", "PRIVATE",
                        "Only bob (or an admin) may read this."));
                log.info("Seeded 4 documents");
            }
        };
    }
}
