package ee.authplayground.resourceserver.appcore.httplogging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders captured HTTP messages and writes them to the traffic logger.
 *
 * <h2>The format</h2>
 * One event per message, one line per event:
 * <pre>
 * &lt;time&gt; [IN  - REQ] [b256e5d5] GET /api/user/master [HEADERS] Host=… | Authorization=… [BODY] -
 * &lt;time&gt; [IN  - RSP] [b256e5d5] GET /api/user/master 200 (22ms) [HEADERS] Content-Type=… [BODY] {…}
 * </pre>
 * The timestamp is <b>not</b> in the message — {@code log4j2-spring.xml} supplies
 * it from the pattern layout, exactly as it does for ordinary application logs.
 * That keeps one clock and one format across both, and means a traffic line and
 * an app line interleaved on stdout are still sortable.
 * <p>
 * Level and logger name are deliberately absent from the layout for this logger.
 * Every traffic line has the same level and the same logger, so those columns
 * would be the same string repeated forever; {@code [IN  - REQ]} is the field
 * that actually discriminates.
 *
 * <h2>Single line, always</h2>
 * Headers are inlined and pipe-delimited, bodies have their newlines collapsed.
 * A multi-line dump reads better in a terminal but is invisible to {@code grep},
 * and being greppable is the point of a log rather than a transcript. Pipes are
 * the delimiter because header values routinely contain commas and semicolons
 * ({@code Cache-Control}, {@code Vary}, {@code WWW-Authenticate}) and essentially
 * never contain a pipe.
 *
 * <h2>Why the logger is named rather than derived from the class</h2>
 * {@code log4j2-spring.xml} routes this name to its own appender with
 * {@code additivity="false"}. Using {@code @Slf4j} would tie that routing to the
 * fully-qualified class name, so renaming or moving this class would silently
 * unroute it — traffic would reappear in the application appender, prefixed, and
 * nothing would fail. A purpose-named logger survives refactoring.
 */
public class HttpTrafficLogger {

    /** Must match the {@code <Logger name="...">} in {@code log4j2-spring.xml}. */
    static final String TRAFFIC_LOGGER_NAME = "ee.authplayground.resourceserver.httptraffic";

    private static final Logger traffic = LoggerFactory.getLogger(TRAFFIC_LOGGER_NAME);

    private static final String HEADER_DELIMITER = " | ";
    private static final String EMPTY_SECTION = "-";

    public void logRequest(LoggedHttpRequest request) {
        if (!traffic.isDebugEnabled()) {
            return;
        }

        StringBuilder line = new StringBuilder(256);
        appendPrefix(line, request.direction(), "REQ", request.correlationId());
        line.append(request.method()).append(' ').append(request.uri());
        appendHeaders(line, request.headers());
        appendBody(line, request.body());

        traffic.debug("{}", line);
    }

    public void logResponse(LoggedHttpResponse response) {
        if (!traffic.isDebugEnabled()) {
            return;
        }

        StringBuilder line = new StringBuilder(256);
        appendPrefix(line, response.direction(), "RSP", response.correlationId());
        line.append(response.method()).append(' ').append(response.uri()).append(' ')
                .append(response.statusCode())
                .append(" (").append(response.durationMillis()).append("ms)");
        appendHeaders(line, response.headers());
        appendBody(line, response.body());

        traffic.debug("{}", line);
    }

    /** The fixed-width part: everything up to here lines up down the log. */
    private void appendPrefix(StringBuilder line, HttpDirection direction, String phase, String correlationId) {
        line.append('[').append(direction.tag()).append(" - ").append(phase).append("] ")
                .append('[').append(correlationId).append("] ");
    }

    private void appendHeaders(StringBuilder line, Map<String, List<String>> headers) {
        line.append(" [HEADERS] ");
        if (headers == null || headers.isEmpty()) {
            line.append(EMPTY_SECTION);
            return;
        }
        line.append(headers.entrySet().stream()
                .map(header -> header.getKey() + "=" + String.join(",", header.getValue()))
                .collect(Collectors.joining(HEADER_DELIMITER)));
    }

    /**
     * Sections are emitted even when empty. Fixed arity is what lets anything —
     * a person's eye included — find the body without parsing what came before.
     */
    private void appendBody(StringBuilder line, String body) {
        line.append(" [BODY] ");
        if (body == null || body.isBlank()) {
            line.append(EMPTY_SECTION);
            return;
        }
        // One event, one line. Spring's JSON is already compact, so this only
        // bites on a hand-formatted payload — but a body with a newline in it
        // would otherwise forge what looks like a second log entry.
        line.append(body.replaceAll("\\R+", " ").strip());
    }
}
