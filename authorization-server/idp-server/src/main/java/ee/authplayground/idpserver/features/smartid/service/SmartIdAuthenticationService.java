package ee.authplayground.idpserver.features.smartid.service;

import ee.authplayground.idpserver.features.smartid.SmartIdProperties;
import ee.authplayground.idpserver.features.smartid.client.Interaction;
import ee.authplayground.idpserver.features.smartid.client.NotificationAuthenticationRequest;
import ee.authplayground.idpserver.features.smartid.client.NotificationAuthenticationResponse;
import ee.authplayground.idpserver.features.smartid.client.SessionStatusResponse;
import ee.authplayground.idpserver.features.smartid.client.SmartIdApi;
import ee.authplayground.idpserver.features.smartid.client.SmartIdEndResult;
import ee.authplayground.idpserver.features.smartid.session.RpChallenge;
import ee.authplayground.idpserver.features.smartid.session.SmartIdSession;
import ee.authplayground.idpserver.features.smartid.session.SmartIdSessionRegistry;
import ee.authplayground.idpserver.features.smartid.validation.EtsiSemanticsIdentifier;
import ee.authplayground.idpserver.features.smartid.validation.SmartIdExpectation;
import ee.authplayground.idpserver.features.smartid.validation.SmartIdResponseValidator;
import ee.authplayground.idpserver.features.smartid.validation.ValidatedSmartIdIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Drives one Smart-ID notification authentication: start a session, poll it,
 * validate what comes back.
 *
 * <h2>Where the flow-specific knowledge lives</h2>
 * Here, and only here. This class knows it is doing the notification flow — it
 * calls the notification endpoint, it declares {@code Notification} as the only
 * acceptable flow type, it sends no callback URL. The validation layer beneath
 * it knows none of that, which is why Phase 4 adds a sibling of this class
 * rather than editing anything underneath.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmartIdAuthenticationService {

    /**
     * Shown on the user's phone. Deliberately naming the action rather than
     * being generic: the whole point of the confirmation screen is that someone
     * who did not initiate a login can tell that something is wrong, and
     * "Confirm" alone gives them nothing to be suspicious about.
     * <p>
     * Kept within the 60-character limit that {@code displayTextAndPIN} imposes.
     */
    private static final String INTERACTION_TEXT_SHORT = "Log in to Auth Playground";

    /** Up to 200 characters, for apps that can show the richer confirmation screen. */
    private static final String INTERACTION_TEXT_LONG =
            "Log in to Auth Playground. If you did not start this login, cancel it.";

    /**
     * Nobody is brokering on our behalf, so this slot is empty — but it is still
     * a slot in the signed payload. Named rather than inlined as {@code ""} so
     * the emptiness reads as a decision.
     */
    private static final String NO_BROKERED_RP_NAME = "";

    /**
     * The notification flow has no callback URL: there is no browser on the
     * handset to return to. Empty, and still occupying its slot.
     */
    private static final String NO_INITIAL_CALLBACK_URL = "";

    private final SmartIdApi smartIdApi;
    private final SmartIdProperties properties;
    private final SmartIdSessionRegistry sessionRegistry;
    private final SmartIdResponseValidator responseValidator;
    private final ObjectMapper objectMapper;

    /**
     * Starts a session: SK pushes a confirmation prompt to the phone registered
     * against this identity.
     *
     * <h2>What the form fields are and are not</h2>
     * The country and national ID typed by the user are <b>addressing
     * information</b>. They decide whose phone rings. They do not establish
     * identity, and nothing later in this flow treats them as if they did — the
     * identity acted upon comes out of the certificate, after the signature
     * verifies.
     */
    public SmartIdSession start(String country, String nationalId) {
        sessionRegistry.evictExpired(properties.sessionMaxDuration());

        EtsiSemanticsIdentifier identifier = EtsiSemanticsIdentifier.of(country, nationalId);
        String rpChallenge = RpChallenge.generate();

        // Serialised ONCE, and the resulting string is what both the request and
        // the later signature check use. Serialising twice from the same objects
        // is the documented way to get a hash mismatch that looks like a forged
        // signature.
        String interactionsBase64 = encodeInteractions();

        NotificationAuthenticationRequest request = new NotificationAuthenticationRequest(
                properties.relyingPartyUuid(),
                properties.relyingPartyName(),
                properties.certificateLevel(),
                NotificationAuthenticationRequest.SIGNATURE_PROTOCOL_ACSP_V2,
                new NotificationAuthenticationRequest.AcspV2RequestParameters(
                        rpChallenge,
                        properties.signatureAlgorithm(),
                        new NotificationAuthenticationRequest.HashAlgorithmParameters(properties.hashAlgorithm())),
                interactionsBase64,
                NotificationAuthenticationRequest.VC_TYPE_NUMERIC4);

        NotificationAuthenticationResponse response =
                smartIdApi.startNotificationAuthentication(identifier.value(), request);

        SmartIdSession session = new SmartIdSession(
                response.sessionID(),
                identifier.value(),
                rpChallenge,
                interactionsBase64,
                RpChallenge.verificationCode(rpChallenge),
                Instant.now());

        sessionRegistry.put(session);
        log.info("Started Smart-ID notification session {} for {}", session.sessionId(), identifier);
        return session;
    }

    /**
     * Asks SK how one session is doing.
     *
     * <h2>Long poll, not a busy loop</h2>
     * SK holds the connection open until the user acts or the timeout expires,
     * so a {@code RUNNING} result means "ask again", not "wait, then ask again".
     * Adding our own sleep on top would double every wait for no benefit.
     */
    public SmartIdPollResult poll(SmartIdSession session) {
        if (session.hasExpired(properties.sessionMaxDuration())) {
            sessionRegistry.remove(session.sessionId());
            return SmartIdPollResult.expired();
        }

        SessionStatusResponse response =
                smartIdApi.getSessionStatus(session.sessionId(), properties.pollTimeout().toMillis());

        if (!response.isComplete()) {
            return SmartIdPollResult.running();
        }

        // Terminal either way from here: the session is spent, and a completed
        // session must not remain pollable. Leaving it would let anyone who
        // learned the session ID collect the result of someone else's login.
        sessionRegistry.remove(session.sessionId());

        SmartIdEndResult endResult = SmartIdEndResult.from(
                response.result() == null ? null : response.result().endResult());

        if (!endResult.isSuccess()) {
            log.info("Smart-ID session {} ended as {}", session.sessionId(), endResult);
            return SmartIdPollResult.failed(endResult);
        }

        // OK means SK is satisfied. We are not, yet — everything that constitutes
        // proof happens in the next line.
        ValidatedSmartIdIdentity identity = responseValidator.validate(response, expectationFor(session));
        return SmartIdPollResult.authenticated(identity);
    }

    /**
     * What we sent, restated for the validator.
     *
     * <h2>Every value here comes from our own session</h2>
     * Not from the response. That is the entire mechanism: reconstructing the
     * signed payload from values the response cannot influence is what makes a
     * verifying signature evidence about <i>this</i> login.
     */
    private SmartIdExpectation expectationFor(SmartIdSession session) {
        return new SmartIdExpectation(
                properties.schemeName(),
                session.rpChallengeBase64(),
                properties.relyingPartyName(),
                NO_BROKERED_RP_NAME,
                session.interactionsBase64(),
                NO_INITIAL_CALLBACK_URL,
                // This flow can only have produced a notification. A response
                // claiming any other flow type did not come from this session.
                Set.of(SmartIdExpectation.FLOW_TYPE_NOTIFICATION),
                properties.certificateLevel(),
                EtsiSemanticsIdentifier.parse(session.semanticsIdentifier()));
    }

    /**
     * The interactions list, JSON then Base64.
     *
     * <h2>Two are sent, in preference order</h2>
     * The richer confirmation screen first, the universally supported one as a
     * fallback. The app picks and tells us which it used, and that choice is
     * signed — so the prompt the user actually saw is a cryptographic fact
     * rather than an assumption.
     */
    private String encodeInteractions() {
        List<Interaction> interactions = List.of(
                Interaction.confirmationMessage(INTERACTION_TEXT_LONG),
                Interaction.displayTextAndPin(INTERACTION_TEXT_SHORT));

        // Jackson 3 (Spring Boot 4) throws unchecked, so there is nothing to
        // catch here. Nothing to catch in practice either: this serialises two
        // records built from constants.
        String json = objectMapper.writeValueAsString(interactions);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
