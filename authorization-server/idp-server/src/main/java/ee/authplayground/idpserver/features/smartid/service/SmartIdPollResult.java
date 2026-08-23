package ee.authplayground.idpserver.features.smartid.service;

import ee.authplayground.idpserver.features.smartid.client.SmartIdEndResult;
import ee.authplayground.idpserver.features.smartid.validation.ValidatedSmartIdIdentity;

/**
 * Where one poll got to: still waiting, finished badly, or proven.
 *
 * <h2>Three outcomes, not two</h2>
 * Modelling this as success-or-failure loses the distinction that matters most
 * to the page: {@link State#RUNNING} is not a failure and not a delay to
 * apologise for — it is a person deciding, and the correct response is to ask
 * again.
 *
 * @param state    what happened
 * @param identity present only when {@link State#AUTHENTICATED} — and reaching that state means
 *                 every cryptographic check passed, not merely that SK said {@code OK}
 * @param failure  present only when {@link State#FAILED}, carrying the message for the user
 */
public record SmartIdPollResult(
        State state,
        ValidatedSmartIdIdentity identity,
        SmartIdEndResult failure
) {

    public enum State {
        /** The user has not answered yet. Poll again. */
        RUNNING,
        /** Verified: signature, chain, level and challenge all check out. */
        AUTHENTICATED,
        /** SK returned a terminal non-OK result — refused, timed out, wrong code. */
        FAILED,
        /** We gave up waiting. Our timeout, not SK's. */
        EXPIRED
    }

    public static SmartIdPollResult running() {
        return new SmartIdPollResult(State.RUNNING, null, null);
    }

    public static SmartIdPollResult authenticated(ValidatedSmartIdIdentity identity) {
        return new SmartIdPollResult(State.AUTHENTICATED, identity, null);
    }

    public static SmartIdPollResult failed(SmartIdEndResult endResult) {
        return new SmartIdPollResult(State.FAILED, null, endResult);
    }

    public static SmartIdPollResult expired() {
        return new SmartIdPollResult(State.EXPIRED, null, null);
    }
}
