package ee.authplayground.userdatamaster.features.users.controller;

import ee.authplayground.userdatamaster.features.users.dto.UserCredentialResponse;
import ee.authplayground.userdatamaster.features.users.entity.UserCredentialType;
import ee.authplayground.userdatamaster.features.users.repository.UserCredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Credential lookup — the login hot path, and the most sensitive endpoint in
 * the system.
 *
 * <h2>Why the master hands out hashes instead of verifying them</h2>
 * The obvious alternative is {@code POST /internal/credentials/verify}: send the
 * plaintext, get a yes/no. It is rejected because it drags authentication
 * policy — lockout, attempt counting, {@code acr} determination, what even
 * counts as success — into the master, or worse, splits it across both
 * services. Read-only lookup leaves every authentication decision in the one
 * service whose job that is.
 * <p>
 * The master is a <b>store</b>, not a verifier.
 *
 * <h2>Only issued credentials pass through here</h2>
 * This endpoint exists for methods where we hold something to compare against.
 * An inherent method like Smart-ID never reaches it: there is no secret and no
 * row, so authentication resolves against the person record instead, via
 * {@code UserDataController}'s national-ID lookup. See {@code UserCredentialType}
 * for the distinction.
 *
 * <h2>The cost, stated plainly</h2>
 * Password hashes cross a network hop. Two controls would normally cover that;
 * this playground has one of them.
 * <ul>
 *   <li><b>Authorization — present.</b> {@code credentials:read} is granted to
 *       exactly one client in the whole services realm. "Only the IdP may see
 *       password hashes" is a config fact you can read out of
 *       {@code playground-services-realm.json}, not a convention someone has to
 *       remember. Combined with the audience check, a token has to be minted by
 *       the right realm, for this service, for that one client.</li>
 *   <li><b>Transport — absent.</b> Everything here is plain {@code http://}.
 *       A real deployment puts TLS (and plausibly mTLS) under this hop. Do not
 *       read "it is scoped" as "it is safe".</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/credentials")
@RequiredArgsConstructor
@Slf4j
public class UserCredentialController {

    private final UserCredentialRepository userCredentialRepository;

    /**
     * Returns the credential <b>and its owner</b> in one response.
     * <p>
     * Both halves travel together because idp-server needs both at the same
     * moment and cannot come back for the second: the form-login POST and the
     * ID-token issuance are different HTTP requests, separated by a browser
     * redirect and Keycloak's back-channel token call. idp-server carries the
     * person attributes forward on the authenticated principal instead.
     * <p>
     * Hence <b>both</b> scopes. It reads as awkward and is actually correct —
     * one read returning two kinds of data, to a caller entitled to both. The
     * invariant that matters is untouched: {@code credentials:read} on its own
     * still buys nothing, and only idp-server holds it.
     * <p>
     * Note this is a lookup, not {@code POST /internal/authn/lookup}. The verb
     * is load-bearing: an authn-shaped endpoint on the master is the first step
     * toward authentication policy migrating into it.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_credentials:read') and hasAuthority('SCOPE_customer:read')")
    @Transactional(readOnly = true)
    public ResponseEntity<UserCredentialResponse> lookup(
            @RequestParam UserCredentialType type,
            @RequestParam String identifier
    ) {
        log.debug("Credential lookup: type={} identifier={}", type, identifier);

        return userCredentialRepository.findByTypeAndIdentifierWithUser(type, identifier)
                .map(UserCredentialResponse::from)
                .map(ResponseEntity::ok)
                // 404 rather than an empty 200, and the caller must treat it exactly
                // like a bad password — never as a distinguishable error, or the
                // endpoint becomes a user-enumeration oracle for whoever holds the
                // scope. Note that "this person has no password" is a perfectly
                // ordinary state: someone who only ever uses Smart-ID has no
                // credential rows at all.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No credential for type=" + type + " identifier=" + identifier));
    }
}
