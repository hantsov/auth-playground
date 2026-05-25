package ee.authplayground.resourceserver.features.user.service;

import ee.authplayground.resourceserver.features.user.command.RegisterUser;
import ee.authplayground.resourceserver.features.user.command.SyncUser;
import ee.authplayground.resourceserver.features.user.entity.UserData;
import ee.authplayground.resourceserver.features.user.repository.UserDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * UserService — owns the application's view of the authenticated user.
 * <p>
 * The resource server is authoritative over <em>application</em> state
 * (name, email, custom JSONB, when we last synced from the IdP).
 * The IdP (Keycloak) is authoritative over <em>identity</em>: the canonical
 * name, email and username are mirrored into the local row on every login
 * via {@link #sync(SyncUser)}.
 * <p>
 * The service deliberately knows nothing about JWTs, sessions, or any other
 * auth transport — callers translate at the boundary into the command types
 * defined in {@code features.user.command}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserDataRepository userDataRepository;

    /**
     * Look up the user row keyed by the stable user id ({@code sub} in JWT
     * terms). Empty {@link Optional} when no row exists — the SPA's account
     * bootstrap interprets that as "needs registration."
     */
    public Optional<UserData> findCurrent(String subject) {
        return userDataRepository.findByKeycloakUserId(subject);
    }

    /**
     * Create the user row from the IdP-supplied identity.
     * Throws 409 Conflict if a row already exists for this subject.
     */
    @Transactional
    public UserData register(RegisterUser registerUserCmd) {
        if (userDataRepository.findByKeycloakUserId(registerUserCmd.subject()).isPresent()) {
            log.warn("Registration attempted for already-provisioned user: {}", registerUserCmd.subject());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "User already registered");
        }

        UserData user = new UserData();
        user.setKeycloakUserId(registerUserCmd.subject());
        user.setUsername(registerUserCmd.username());
        user.setEmail(registerUserCmd.email());
        user.setFirstName(registerUserCmd.firstName());
        user.setLastName(registerUserCmd.lastName());
        user.setLastSyncedAt(LocalDateTime.now());

        UserData saved = userDataRepository.save(user);
        log.info("Provisioned user row id={} keycloak_user_id={}", saved.getId(), registerUserCmd.subject());
        return saved;
    }

    /**
     * Mirror the IdP-supplied identity into the existing user row.
     * <p>
     * The IdP is canonical: nullable fields ({@code email},
     * {@code firstName}, {@code lastName}) follow the command value
     * verbatim, including nulls. {@code username} is constrained NOT NULL
     * by the schema, so it is only overwritten when the command carries a
     * non-blank value — in practice it always does, since that field is how
     * the user authenticated.
     * <p>
     * {@code custom_data} (JSONB) is application-owned and is never
     * touched by sync.
     */
    @Transactional
    public UserData sync(SyncUser syncUserCmd) {
        UserData user = userDataRepository.findByKeycloakUserId(syncUserCmd.subject())
                .orElseThrow(() -> {
                    log.warn("Sync requested for unprovisioned user: {}", syncUserCmd.subject());
                    return new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "User not registered");
                });

        if (syncUserCmd.username() != null && !syncUserCmd.username().isBlank()) {
            user.setUsername(syncUserCmd.username());
        }
        user.setEmail(syncUserCmd.email());
        user.setFirstName(syncUserCmd.firstName());
        user.setLastName(syncUserCmd.lastName());
        user.setLastSyncedAt(LocalDateTime.now());

        UserData saved = userDataRepository.save(user);
        log.debug("Synced user row id={} from IdP", saved.getId());
        return saved;
    }
}
