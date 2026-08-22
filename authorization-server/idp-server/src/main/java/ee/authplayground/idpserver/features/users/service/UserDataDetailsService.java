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
 * records.
 *
 * <h2>This is the password path only</h2>
 * Password is an <i>issued</i> credential: we hold a hash, so there is a row to
 * fetch. Smart-ID is <i>inherent</i> — the state issued the identity, SK holds
 * the key, and the national ID on the person record is the whole binding. It
 * resolves through the master's national-ID lookup instead of a credential
 * lookup, and never reaches this class. Both paths converge on the same
 * {@code users.id}, which is what lets one person arrive at one {@code sub} by
 * either method.
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
            // Belt and braces: the master's `password_requires_secret` CHECK
            // constraint makes this unreachable, because a PASSWORD row cannot
            // exist without a hash. Kept because the alternative failure — handing
            // Spring Security a null to compare against — is silent and awful.
            //
            // Note this is NOT the "user has no password" case. Someone who only
            // uses Smart-ID has no credential row at all, so the lookup 404s and
            // we never get here.
            throw new UsernameNotFoundException("PASSWORD credential has no hash: " + loginName);
        }

        log.debug("Resolved login '{}' to subject {}", loginName, credential.user().id());

        return UserDataDetails.of(credential.user(), credential.secretHash(), credential.enabled());
    }
}
