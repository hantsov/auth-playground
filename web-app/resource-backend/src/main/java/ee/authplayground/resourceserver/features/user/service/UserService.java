package ee.authplayground.resourceserver.features.user.service;

import ee.authplayground.resourceserver.features.user.entity.UserData;
import ee.authplayground.resourceserver.features.user.repository.UserDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * Keycloak is authoritative over <em>identity</em>: the canonical name,
 * email and username live in the JWT and are mirrored into the local row
 * on every login via {@link #syncFromJwt(Jwt)}.
 * <p>
 * Two flows feed into this service:
 * <ol>
 *   <li><b>Registration</b> ({@link #register(Jwt)}): first time the
 *       resource server sees a {@code sub}, the user confirms in the SPA
 *       and a row is created from the JWT claims.</li>
 *   <li><b>Sync</b> ({@link #syncFromJwt(Jwt)}): on every subsequent
 *       login bootstrap, the SPA asks the backend to refresh local
 *       fields from the current token so the app stays in step with the
 *       IdP.</li>
 * </ol>
 * Pure reads are served via {@link #findCurrent(Jwt)} which never writes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserDataRepository userDataRepository;

    /**
     * Look up the user row keyed by the JWT's {@code sub} claim.
     * Empty {@link Optional} when no row exists — the SPA's account
     * bootstrap interprets that as "needs registration."
     */
    public Optional<UserData> findCurrent(Jwt jwt) {
        return userDataRepository.findByKeycloakUserId(jwt.getSubject());
    }

    /**
     * Create the user row from the validated JWT.
     * Throws 409 Conflict if a row already exists for this {@code sub}.
     * Throws 400 if the JWT is missing {@code preferred_username}, which
     * the schema requires (the NOT NULL constraint on {@code username}).
     */
    @Transactional
    public UserData register(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();

        if (userDataRepository.findByKeycloakUserId(keycloakUserId).isPresent()) {
            log.warn("Registration attempted for already-provisioned user: {}", keycloakUserId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "User already registered");
        }

        String preferredUsername = jwt.getClaim("preferred_username");
        if (preferredUsername == null || preferredUsername.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "JWT is missing preferred_username");
        }

        UserData user = new UserData();
        user.setKeycloakUserId(keycloakUserId);
        user.setUsername(preferredUsername);
        user.setEmail(jwt.getClaim("email"));
        user.setFirstName(jwt.getClaim("given_name"));
        user.setLastName(jwt.getClaim("family_name"));
        user.setLastSyncedAt(LocalDateTime.now());

        UserData saved = userDataRepository.save(user);
        log.info("Provisioned user row id={} keycloak_user_id={}", saved.getId(), keycloakUserId);
        return saved;
    }

    /**
     * Mirror the current JWT claims into the existing user row.
     * <p>
     * Keycloak is canonical: nullable fields ({@code email},
     * {@code first_name}, {@code last_name}) follow the claim value
     * verbatim, including nulls. {@code username} is constrained NOT NULL
     * by the schema, so it is only overwritten when the JWT carries a
     * non-blank {@code preferred_username} — in practice it always does,
     * since that claim is how the user authenticated.
     * <p>
     * {@code custom_data} (JSONB) is application-owned and is never
     * touched by sync.
     */
    @Transactional
    public UserData syncFromJwt(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();

        UserData user = userDataRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> {
                    log.warn("Sync requested for unprovisioned user: {}", keycloakUserId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "User not registered");
                });

        String preferredUsername = jwt.getClaim("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            user.setUsername(preferredUsername);
        }
        user.setEmail(jwt.getClaim("email"));
        user.setFirstName(jwt.getClaim("given_name"));
        user.setLastName(jwt.getClaim("family_name"));
        user.setLastSyncedAt(LocalDateTime.now());

        UserData saved = userDataRepository.save(user);
        log.debug("Synced user row id={} from JWT", saved.getId());
        return saved;
    }
}
