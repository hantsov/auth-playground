package ee.authplayground.idpserver.features.smartid.service;

import ee.authplayground.idpserver.features.smartid.validation.EtsiSemanticsIdentifier;
import ee.authplayground.idpserver.features.smartid.validation.ValidatedSmartIdIdentity;
import ee.authplayground.idpserver.features.users.client.UserDataResponse;
import ee.authplayground.idpserver.features.users.client.UserMasterClient;
import ee.authplayground.idpserver.features.users.service.UserDataDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Resolves a proven Smart-ID identity to a person we know.
 *
 * <h2>The lookup is by person, not by credential</h2>
 * The password path fetches a {@code user_credentials} row. This path
 * deliberately does not, and there is no {@code SMART_ID} row to fetch: Smart-ID
 * is an <i>inherent</i> method. The state issued the identity, SK holds the
 * private key, and {@code users.national_id} + {@code users.nationality} are the
 * entire binding. Adding a credential row would duplicate a derivable identifier
 * and imply an enrolment step that does not exist.
 * <p>
 * What the two paths share is the shape <i>after</i> resolution, not the lookup:
 * identifier &rarr; {@code users.id} &rarr; principal &rarr; the same
 * {@code sub}. One person, two methods, one subject — at two assurance levels.
 *
 * <h2>A miss is a rejection in this phase</h2>
 * Smart-ID authenticated someone we have no record of. That is not an
 * authentication failure — the cryptography was perfect — it is an
 * authorization one, and it is exactly the point where Phase 3 will insert
 * registration. Until then it is refused, loudly and distinctly, because the
 * failure mode to avoid is a silent pass-through that logs an unknown person in.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartIdAuthenticationProvider implements AuthenticationProvider {

    private final UserMasterClient userMasterClient;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        ValidatedSmartIdIdentity identity = (ValidatedSmartIdIdentity) authentication.getPrincipal();
        EtsiSemanticsIdentifier identifier = identity.identifier();

        UserDataResponse user = userMasterClient
                .findByNationalId(identifier.nationalId(), identifier.country())
                .orElseThrow(() -> {
                    // Its own log line, and worth reading twice when it appears:
                    // a real person with a valid qualified certificate reached us
                    // and we turned them away. In Phase 3 this becomes a
                    // registration; today it is the boundary being enforced.
                    log.info("Smart-ID identity {} authenticated but is unknown to the user data master "
                            + "— rejecting (Phase 3 turns this into registration)", identifier);
                    return new UsernameNotFoundException(
                            "No user with national id " + identifier.country() + "-" + identifier.nationalId());
                });

        if (!user.enabled()) {
            // Distinct from "unknown". We know exactly who this is and are
            // declining anyway, which is a different conversation with support.
            throw new DisabledException("User " + user.id() + " is disabled");
        }

        log.debug("Resolved Smart-ID identity {} to subject {}", identifier, user.id());
        return SmartIdAuthenticationToken.authenticated(UserDataDetails.ofInherentMethod(user));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return SmartIdAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
