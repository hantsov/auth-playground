package ee.authplayground.resourceserver.appcore.httplogging;

import java.util.List;
import java.util.Map;

/**
 * A response as captured for logging, <b>already masked</b>.
 *
 * <h2>Why it repeats the request's method and URI</h2>
 * Strictly redundant — the correlation id already pairs this with its request.
 * It is repeated so that <b>every line stands alone</b>: grepping for
 * {@code /api/user/master} returns both halves of the exchange, rather than the
 * request plus an id you then have to pivot through in a second search. On a log
 * whose purpose is to be read by a person chasing one flow, that is worth a few
 * duplicated characters.
 */
public record LoggedHttpResponse(
        HttpDirection direction,
        String correlationId,
        String method,
        String uri,
        int statusCode,
        long durationMillis,
        Map<String, List<String>> headers,
        String body
) {
}
