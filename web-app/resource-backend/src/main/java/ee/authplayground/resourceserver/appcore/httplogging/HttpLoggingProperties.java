package ee.authplayground.resourceserver.appcore.httplogging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * The entire configuration surface of HTTP traffic logging.
 *
 * <h2>Masking defaults to OFF, and that is the design</h2>
 * A production HTTP logger masks by default and requires deliberate action to
 * unmask. This one does the opposite. The premise of this playground is that a
 * reader can follow a token from idp-server through Keycloak to the SPA and
 * <b>read it at every hop</b>; a logger that redacts the token defeats the
 * exercise. It is the same trade as the weak-credentials-on-purpose convention
 * in AGENTS.md.
 * <p>
 * The masking mechanism is fully implemented and switchable — so the shape of a
 * correct implementation is on record, and flipping one flag demonstrates it.
 * <b>What must never happen is this default travelling out of the playground.</b>
 *
 * @param level          not read by this class — {@code log4j2-spring.xml} reads it
 *                       through Boot's {@code spring:} lookup as the traffic
 *                       logger's level. Declared here so it is documented and
 *                       validated in one place with everything else.
 * @param maxBodyBytes   applied to the body <i>after</i> masking, and counted in
 *                       characters rather than bytes. Masking first matters: a
 *                       body truncated mid-JSON no longer parses, and an
 *                       unparseable body cannot be masked at all.
 * @param excludePaths   matched with {@code PathPattern}, so {@code /actuator/**}
 *                       has its usual meaning.
 */
@ConfigurationProperties("playground.http-logging")
public record HttpLoggingProperties(

        @DefaultValue("true") boolean enabled,
        @DefaultValue("DEBUG") String level,
        @DefaultValue("true") boolean includeHeaders,
        @DefaultValue("true") boolean includeBodies,
        @DefaultValue("8192") int maxBodyBytes,
        @DefaultValue("/actuator/**") List<String> excludePaths,
        @DefaultValue Masking masking
) {

    public HttpLoggingProperties {
        excludePaths = excludePaths == null ? List.of() : List.copyOf(excludePaths);
        masking = masking == null ? Masking.allOff() : masking;
    }

    /**
     * @param jsonFields field names replaced wherever they appear, <b>at any
     *                   depth</b>. Depth is not a detail: the master's credential
     *                   response nests {@code nationalId} one level down inside
     *                   {@code user}, and a top-level key match would sail past it.
     */
    public record Masking(
            @DefaultValue("false") boolean authTokens,
            @DefaultValue("false") boolean cookies,
            @DefaultValue List<String> jsonFields
    ) {

        public Masking {
            jsonFields = jsonFields == null ? List.of() : List.copyOf(jsonFields);
        }

        static Masking allOff() {
            return new Masking(false, false, List.of());
        }
    }
}
