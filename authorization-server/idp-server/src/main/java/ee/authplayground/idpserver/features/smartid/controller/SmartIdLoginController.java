package ee.authplayground.idpserver.features.smartid.controller;

import ee.authplayground.idpserver.features.smartid.client.SmartIdSessionNotFoundException;
import ee.authplayground.idpserver.features.smartid.client.SmartIdUnavailableException;
import ee.authplayground.idpserver.features.smartid.client.SmartIdUserNotFoundException;
import ee.authplayground.idpserver.features.smartid.service.SmartIdAuthenticationService;
import ee.authplayground.idpserver.features.smartid.service.SmartIdAuthenticationToken;
import ee.authplayground.idpserver.features.smartid.service.SmartIdPollResult;
import ee.authplayground.idpserver.features.smartid.session.SmartIdSession;
import ee.authplayground.idpserver.features.smartid.session.SmartIdSessionRegistry;
import ee.authplayground.idpserver.features.smartid.validation.SmartIdValidationException;
import ee.authplayground.idpserver.features.users.client.UserMasterUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints the login page talks to: start a Smart-ID session, then ask
 * how it is going.
 *
 * <h2>The browser polls us, never SK</h2>
 * It would be shorter to hand the page SK's session ID and let it poll
 * {@code sid.demo.sk.ee} directly. That would move the trust decision into
 * JavaScript, where every check this phase exists for — signature, chain,
 * certificate level, challenge — would be either absent or unenforceable.
 * Anything a browser concludes about a Smart-ID response is a claim, not a
 * verification.
 *
 * <h2>What crosses to the browser, and what never does</h2>
 * Only a session ID and the verification code. The session ID is safe: knowing
 * it lets you ask how a login is going, not complete one, because completing one
 * requires a signature from a key on the user's phone.
 * <p>
 * Phase 4's device-link flow adds per-session secret material to the registry,
 * and <b>that must never appear in any response here.</b> The response records
 * below are deliberately narrow rather than "return the session object" for
 * exactly that reason — a serialiser pointed at the whole session is how a
 * secret leaks the day someone adds a field to it.
 */
@RestController
@RequestMapping("/login/smart-id")
@RequiredArgsConstructor
@Slf4j
public class SmartIdLoginController {

    private final SmartIdAuthenticationService authenticationService;
    private final SmartIdSessionRegistry sessionRegistry;
    private final AuthenticationManager authenticationManager;

    /** Where the authenticated context is persisted, so the OAuth2 redirect finds it. */
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    /**
     * Where Spring Security stashed {@code /oauth2/authorize} when it bounced the
     * browser here. Completing the login has to send the user back to it, or the
     * authorization request they arrived with is simply lost.
     */
    private final RequestCache requestCache = new HttpSessionRequestCache();

    /**
     * Changes the session ID on successful authentication.
     *
     * <h2>Not optional, and easy to omit here</h2>
     * Spring Security's form-login filter does this for us on the password path.
     * This path does not go through that filter, so it has to be done explicitly
     * — otherwise an attacker who planted a known session ID in the victim's
     * browser before login still holds a valid one afterwards. Session fixation
     * is the one thing that quietly stops being handled when authentication
     * moves out of the standard filter.
     */
    private final ChangeSessionIdAuthenticationStrategy sessionStrategy = new ChangeSessionIdAuthenticationStrategy();

    /**
     * Starts a session and sends a push notification to the user's phone.
     *
     * @return the session ID to poll, and the four digits to display
     */
    @PostMapping("/start")
    public StartResponse start(@RequestBody StartRequest request) {
        SmartIdSession session = authenticationService.start(request.country(), request.nationalId());
        return new StartResponse(session.sessionId(), session.verificationCode());
    }

    /**
     * One long poll. Returns quickly with {@code RUNNING} while the user is
     * still deciding, which is a normal outcome rather than a failure.
     *
     * <h2>Authentication happens here, on the poll</h2>
     * Slightly unusual, and it follows from the protocol: the moment a login
     * becomes real is the moment SK reports a verified completion, and that
     * arrives on a poll rather than on a form submission. So this is the request
     * that establishes the security context.
     */
    @GetMapping("/status")
    public StatusResponse status(
            @RequestParam String sessionId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SmartIdSession session = sessionRegistry.find(sessionId)
                .orElseThrow(() -> new SmartIdSessionNotFoundException("Unknown or completed session " + sessionId));

        SmartIdPollResult result = authenticationService.poll(session);

        return switch (result.state()) {
            case RUNNING -> StatusResponse.running();
            case FAILED -> StatusResponse.failed(result.failure().userMessage());
            case EXPIRED -> StatusResponse.failed("The login attempt timed out. Please try again.");
            case AUTHENTICATED -> establishSession(result, request, response);
        };
    }

    /**
     * Turns a verified identity into a logged-in session.
     *
     * <h2>Through the AuthenticationManager, not around it</h2>
     * The provider chain is what resolves the identity to a person and decides
     * whether we know them. Constructing a principal here directly would work
     * and would put an authorization decision in a controller, where nothing
     * else in this server makes one.
     */
    private StatusResponse establishSession(
            SmartIdPollResult result,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new SmartIdAuthenticationToken(result.identity()));
        } catch (UserMasterUnavailableException e) {
            // Rethrown rather than handled here, and the ordering of these two
            // catch blocks is the entire point.
            //
            // UserMasterUnavailableException extends AuthenticationException on
            // purpose, so that the password path can carry it down the form-login
            // failure route. The cost is that the broad catch below would swallow
            // it and tell the user "there is no account for it here" — reporting
            // an outage as a fact about their identity, and sending whoever
            // debugs it to look at the database instead of at the token.
            //
            // "We could not ask" is not "we asked and the answer was no."
            throw e;
        } catch (AuthenticationException e) {
            // Smart-ID proved who they are; we simply have no record of them, or
            // are declining anyway. Emphatically NOT "authentication failed" —
            // the cryptography was perfect. Phase 3 turns this into registration.
            log.info("Smart-ID identity {} rejected: {}", result.identity().identifier(), e.getMessage());
            return StatusResponse.failed(
                    "Your identity was verified, but there is no account for it here.");
        }

        sessionStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        log.info("Smart-ID login complete for subject {}", authentication.getName());
        return StatusResponse.authenticated(redirectTarget(request, response));
    }

    /**
     * Back to the authorization request the user arrived with, or to the root if
     * they came to {@code /login} directly.
     */
    private String redirectTarget(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        return savedRequest != null ? savedRequest.getRedirectUrl() : "/";
    }

    /**
     * No Smart-ID account for the national ID the user typed.
     * <p>
     * Distinct from "we don't know you": nothing was pushed to any phone,
     * because there is no Smart-ID subscription behind that number at all.
     */
    @ExceptionHandler(SmartIdUserNotFoundException.class)
    public ResponseEntity<StatusResponse> onNoSmartIdAccount(SmartIdUserNotFoundException e) {
        log.debug("No Smart-ID account: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(StatusResponse.failed(
                "No Smart-ID account was found for that identification number."));
    }

    @ExceptionHandler(SmartIdSessionNotFoundException.class)
    public ResponseEntity<StatusResponse> onExpiredSession(SmartIdSessionNotFoundException e) {
        log.debug("Smart-ID session gone: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GONE).body(StatusResponse.failed(
                "This login attempt has expired. Please try again."));
    }

    /**
     * A response claimed success and then failed a cryptographic check.
     *
     * <h2>Logged at warn, reported vaguely</h2>
     * In development this is nearly always our own payload reconstruction being
     * one byte out. In production it is someone forging a response. The two look
     * identical from here, so the log gets the detail and the user gets a
     * generic message — telling an attacker which check caught them is free
     * tuning information.
     */
    @ExceptionHandler(SmartIdValidationException.class)
    public ResponseEntity<StatusResponse> onValidationFailure(SmartIdValidationException e) {
        log.warn("Smart-ID response failed validation: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(StatusResponse.failed(
                "Smart-ID authentication could not be verified."));
    }

    /**
     * SK or the user data master is unreachable or misconfigured.
     * <p>
     * Kept apart from every user-facing failure above on purpose. "Our
     * relying-party UUID is not registered for this source IP" and "the user
     * tapped Cancel" are indistinguishable on the login page and completely
     * different problems for whoever has to fix one — collapsing them is how an
     * afternoon disappears at the wrong layer.
     */
    @ExceptionHandler({SmartIdUnavailableException.class, UserMasterUnavailableException.class})
    public ResponseEntity<StatusResponse> onUpstreamFailure(RuntimeException e) {
        log.error("Smart-ID login could not proceed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(StatusResponse.failed(
                "Smart-ID is temporarily unavailable. Please try again shortly."));
    }

    /**
     * What the user typed. Addressing information only — it decides whose phone
     * rings and nothing else. Identity comes from the certificate.
     */
    public record StartRequest(String country, String nationalId) {
    }

    /**
     * @param sessionId        safe to expose; it permits asking about a login, not completing one
     * @param verificationCode the four digits the user compares against their phone. Displaying it
     *                         prominently is a security control: an attacker who tricks someone into
     *                         starting a login cannot make their own screen show the victim's code.
     */
    public record StartResponse(String sessionId, String verificationCode) {
    }

    /**
     * @param state       {@code RUNNING}, {@code AUTHENTICATED} or {@code FAILED}
     * @param message     shown to the user on failure
     * @param redirectUrl where to go once authenticated — the authorization request they arrived with
     */
    public record StatusResponse(String state, String message, String redirectUrl) {

        static StatusResponse running() {
            return new StatusResponse("RUNNING", null, null);
        }

        static StatusResponse authenticated(String redirectUrl) {
            return new StatusResponse("AUTHENTICATED", null, redirectUrl);
        }

        static StatusResponse failed(String message) {
            return new StatusResponse("FAILED", message, null);
        }
    }
}
