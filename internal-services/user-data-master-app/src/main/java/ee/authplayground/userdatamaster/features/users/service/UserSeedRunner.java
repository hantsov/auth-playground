package ee.authplayground.userdatamaster.features.users.service;

import ee.authplayground.userdatamaster.features.users.entity.UserCredential;
import ee.authplayground.userdatamaster.features.users.entity.UserCredentialType;
import ee.authplayground.userdatamaster.features.users.entity.UserData;
import ee.authplayground.userdatamaster.features.users.repository.UserCredentialRepository;
import ee.authplayground.userdatamaster.features.users.repository.UserDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the playground's canonical test identities on first boot. Idempotent —
 * re-running against an existing database is a no-op, because each insertion
 * checks {@code existsByUsername} first.
 * <p>
 * The seed lives in application code rather than a Flyway migration so the
 * BCrypt hash is computed by the same encoder the login flow uses. A
 * pre-computed hash in SQL couples the migration to one cost factor and
 * algorithm version forever; this way passwords are re-hashed at the encoder's
 * current settings on every fresh database.
 * <p>
 * <b>Two rows per person here, but not two in general.</b> That is the
 * credential split made concrete: a {@code users} row saying who they are, and
 * a {@code PASSWORD} credential — an <i>issued</i> method — saying what we gave
 * them to prove it with.
 * <p>
 * Smart-ID adds neither. It is an inherent method: the {@code national_id} and
 * {@code nationality} seeded below are the entire binding, and Phase 2 turns
 * them on with no data change and no new row. See {@code UserCredentialType}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserSeedRunner implements CommandLineRunner {

    private final UserDataRepository userDataRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        // The national IDs are Smart-ID's published demo identity codes. Seeding
        // them now costs nothing and means Phase 2's happy path works against a
        // known identity with no data change and no writes at all: the national
        // ID *is* the Smart-ID binding, so these two people can already
        // authenticate that way the moment the protocol layer exists.
        seed("conan", "40404040009", "EE", "conan@playground.local", "Conan", "Barbarian", "conan123");
        seed("matrix", "50001029996", "EE", "matrix@playground.local", "John", "Matrix", "matrix123");
    }

    private void seed(
            String username,
            String nationalId,
            String nationality,
            String email,
            String givenName,
            String familyName,
            String plaintextPassword
    ) {
        if (userDataRepository.existsByUsername(username)) {
            log.debug("Seed user already present: {}", username);
            return;
        }

        UserData user = new UserData();
        user.setUsername(username);
        user.setNationalId(nationalId);
        user.setNationality(nationality);
        user.setEmail(email);
        // These addresses are fixtures we control, which is the only circumstance
        // under which asserting verification is honest. A form-collected address
        // in Phase 3 gets `false` until something actually verifies it.
        user.setEmailVerified(true);
        user.setGivenName(givenName);
        user.setFamilyName(familyName);
        user.setEnabled(true);
        userDataRepository.save(user);

        UserCredential credential = new UserCredential();
        credential.setUser(user);
        credential.setType(UserCredentialType.PASSWORD);
        // For PASSWORD the identifier is the login name. It duplicates
        // users.username today and will stop doing so the moment anyone renames
        // themselves — which is the point: the handle is display, this is the key.
        credential.setIdentifier(username);
        credential.setSecretHash(passwordEncoder.encode(plaintextPassword));
        credential.setEnabled(true);
        userCredentialRepository.save(credential);

        log.info("Seeded user: {} ({}) id={} national-id={}",
                username, email, user.getId(), user.toSemanticsIdentifier());
    }
}
