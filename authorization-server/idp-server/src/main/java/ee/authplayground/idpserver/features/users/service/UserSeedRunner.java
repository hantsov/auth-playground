package ee.authplayground.idpserver.features.users.service;

import ee.authplayground.idpserver.features.users.entity.UserData;
import ee.authplayground.idpserver.features.users.repository.UserDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the user store with the playground's canonical test identities
 * on first boot. Idempotent — re-running this against an existing DB is a
 * no-op because each insertion checks {@code existsByUsername} first.
 * <p>
 * The seed lives in application code (rather than a Flyway SQL migration)
 * so the BCrypt hash is computed by the same encoder the login flow uses.
 * Storing a pre-computed hash in SQL would couple the migration to a
 * specific cost factor and algorithm version forever; this way the
 * passwords are re-hashed at the encoder's current settings on every
 * fresh database.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserSeedRunner implements CommandLineRunner {

    private final UserDataRepository userDataRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        seed("conan", "conan@playground.local", "Conan", "Barbarian", "conan123");
        seed("matrix", "matrix@playground.local", "John", "Matrix", "matrix123");
    }

    private void seed(String username, String email, String givenName, String familyName, String plaintextPassword) {
        if (userDataRepository.existsByUsername(username)) {
            log.debug("Seed user already present: {}", username);
            return;
        }
        UserData user = new UserData();
        user.setUsername(username);
        user.setEmail(email);
        user.setGivenName(givenName);
        user.setFamilyName(familyName);
        user.setPasswordHash(passwordEncoder.encode(plaintextPassword));
        user.setEnabled(true);
        userDataRepository.save(user);
        log.info("Seeded user: {} ({})", username, email);
    }
}
