package ee.authplayground.idpserver.features.smartid.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds in-flight Smart-ID sessions between the request that starts one and the
 * polls that finish it.
 *
 * <h2>Why not the HTTP session</h2>
 * Stashing this on {@code HttpSession} would be shorter and is the obvious move
 * for a notification-only flow. It is avoided deliberately: the device-link flow
 * in Phase 4 has per-session secret material and timing that the browser must
 * never see, and a design that assumes "one browser session, one Smart-ID
 * session, all of it safe to hand to the page" does not extend to that. Building
 * the registry now costs a class; retrofitting it later costs every call site.
 *
 * <h2>In-memory, and that is a real limitation</h2>
 * A restart drops every in-flight login, and a second instance would not see the
 * first one's sessions. Correct for a playground and stated rather than hidden.
 * A real deployment needs this shared — Redis, or a database table — which is a
 * different design, not a bigger map.
 *
 * <h2>The key is not a secret</h2>
 * SK's session ID reaches the browser, because the page polls our status
 * endpoint with it. That is safe on its own: knowing a session ID lets you ask
 * how a login is going, not complete one. Completing it requires a signature
 * from a private key held on the user's phone. Anything here that <i>would</i>
 * be dangerous in a browser — Phase 4's session secret — must never be returned
 * by the status endpoint.
 */
@Component
@Slf4j
public class SmartIdSessionRegistry {

    private final Map<String, SmartIdSession> sessions = new ConcurrentHashMap<>();

    public void put(SmartIdSession session) {
        sessions.put(session.sessionId(), session);
        log.debug("Registered Smart-ID session {} for {}",
                session.sessionId(), session.semanticsIdentifier());
    }

    public Optional<SmartIdSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Drops a session once it has been resolved.
     *
     * <h2>Single use, on purpose</h2>
     * A completed session must not be pollable twice. The response carries a
     * signature over our challenge, and leaving it retrievable would let a
     * second caller who learned the session ID pick up the result of someone
     * else's authentication.
     */
    public void remove(String sessionId) {
        if (sessions.remove(sessionId) != null) {
            log.debug("Removed Smart-ID session {}", sessionId);
        }
    }

    /**
     * Clears sessions abandoned mid-flow — the user closed the tab, so nothing
     * ever polls them to completion and nothing ever removes them.
     * <p>
     * Called opportunistically rather than on a scheduler: this is a playground
     * with a handful of logins, and a {@code @Scheduled} bean would be more
     * machinery than the problem deserves. A real deployment with a shared store
     * gets this from the store's own TTL.
     */
    public void evictExpired(Duration maxDuration) {
        Instant cutoff = Instant.now().minus(maxDuration);
        sessions.entrySet().removeIf(entry -> {
            boolean stale = entry.getValue().startedAt().isBefore(cutoff);
            if (stale) {
                log.debug("Evicting abandoned Smart-ID session {}", entry.getKey());
            }
            return stale;
        });
    }

    /** Visible for the actuator-less "is anything stuck" question during development. */
    public int size() {
        return sessions.size();
    }
}
