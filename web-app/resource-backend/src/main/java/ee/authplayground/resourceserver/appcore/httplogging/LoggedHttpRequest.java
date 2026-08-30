package ee.authplayground.resourceserver.appcore.httplogging;

import java.util.List;
import java.util.Map;

/**
 * A request as captured for logging, <b>already masked</b>.
 *
 * <h2>Why request and response are separate types</h2>
 * They are logged at different moments. The request line is emitted the instant
 * the request arrives — before the handler runs — so that a handler which hangs,
 * deadlocks or dies still leaves evidence that the request existed. A single
 * type spanning both halves could only ever be emitted at the end, which is
 * exactly the case where it would tell you nothing.
 *
 * @param body {@code null} when there is nothing to show. A body that was not
 *             captured — wrong content type, no declared length, or larger than
 *             the capture limit — is a placeholder describing it rather than its
 *             content.
 */
public record LoggedHttpRequest(
        HttpDirection direction,
        String correlationId,
        String method,
        String uri,
        Map<String, List<String>> headers,
        String body
) {
}
