package ee.authplayground.idpserver.features.users.service;

import ee.authplayground.idpserver.features.users.client.UserCredentialResponse;
import ee.authplayground.idpserver.features.users.client.UserMasterClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridges Spring Security's form-login filter to the user data master.
 * <p>
 * There is no local user store behind this — no repository, no entity, no
 * datasource. The form-login filter calls {@link #loadUserByUsername(String)},
 * we fetch the credential over HTTP, and hand Spring Security the stored hash
 * to compare against the submitted plaintext.
 *
 * <h2>The master is a store, not a verifier</h2>
 * The comparison happens <b>here</b>, against a hash fetched from the master.
 * The alternative — posting the plaintext to the master and getting a yes/no —
 * would drag authentication policy (lockout, attempt counting, {@code acr}
 * determination, what counts as success) into a service whose job is to hold
 * records. It is also asymmetric for no reason: the Smart-ID path has no secret
 * to verify at all, only a lookup. Reading makes both methods work the same way.
 *
 * <h2>What "username" means here</h2>
 * The parameter is whatever was typed into the login form, and it is matched
 * against {@code user_credentials.identifier} on the PASSWORD row — not against
 * {@code users.username}. The two happen to be equal for seeded users and are
 * free to diverge: one is a login key, the other a display handle.
 * <p>
 * The returned {@link UserDataDetails} then carries the master's user ID as its
 * username, which is what makes {@code sub} a stable UUID.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDataDetailsService implements UserDetailsService {

    private final UserMasterClient userMasterClient;

    @Override
    public UserDetails loadUserByUsername(String loginName) throws UsernameNotFoundException {
        UserCredentialResponse credential = userMasterClient.findPasswordCredential(loginName)
                .orElseThrow(() -> new UsernameNotFoundException("No PASSWORD credential for: " + loginName));

        if (credential.secretHash() == null) {
            // A PASSWORD row without a hash is a broken record, not a user who
            // "has no password" — a Smart-ID-only person simply has no PASSWORD
            // row at all. Refuse rather than hand Spring Security a null to
            // compare against.
            throw new UsernameNotFoundException("PASSWORD credential has no hash: " + loginName);
        }

        log.debug("Resolved login '{}' to subject {}", loginName, credential.user().id());

        return UserDataDetails.of(credential.user(), credential.secretHash(), credential.enabled());
    }
}
