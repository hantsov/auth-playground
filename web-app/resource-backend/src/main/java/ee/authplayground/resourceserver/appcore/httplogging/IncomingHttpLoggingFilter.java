package ee.authplayground.resourceserver.appcore.httplogging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Logs every HTTP request and response this service handles.
 *
 * <h2>It knows about servlets, and nothing else</h2>
 * Masking lives in {@link HttpLogMasker}, formatting in
 * {@link HttpTrafficLogger}. This class wraps, captures bytes, delegates, and
 * hands off. If it ever starts deciding what is secret or what a log line looks
 * like, the split has gone wrong.
 *
 * <h2>Two events, not one</h2>
 * The request is logged <b>on arrival</b>, before the handler runs; the response
 * afterwards. The alternative — one event describing the whole exchange, emitted
 * at the end — cannot report a request whose handler hangs or dies, which is
 * precisely the situation where you most want to know a request arrived. It also
 * timestamps the request with the moment the response finished, which is a small
 * lie that eventually costs somebody an hour.
 *
 * <h2>Where it sits, and why that is in front of Spring Security</h2>
 * Registered one step ahead of the security chain (see {@code HttpLoggingConfig}),
 * so <b>401s and 403s get logged</b> — in a playground about authentication those
 * are the interesting failures, and a filter placed after security never sees
 * them.
 * <p>
 * The cost is that no {@code Authentication} exists yet when this runs, so log
 * lines carry no principal. That is a deliberate trade: the correlation id
 * stitches a request to everything downstream of it, and deriving a principal
 * here would mean parsing a JWT that Spring Security has not validated yet.
 */
@Slf4j
public class IncomingHttpLoggingFilter extends OncePerRequestFilter {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private static final int CORRELATION_ID_LENGTH = 8;
    private static final int MAX_INBOUND_CORRELATION_ID_LENGTH = 64;

    private final HttpLoggingProperties properties;
    private final HttpLogMasker masker;
    private final HttpTrafficLogger trafficLogger;
    private final List<PathPattern> excludedPaths;

    public IncomingHttpLoggingFilter(HttpLoggingProperties properties,
                                     HttpLogMasker masker,
                                     HttpTrafficLogger trafficLogger) {
        this.properties = properties;
        this.masker = masker;
        this.trafficLogger = trafficLogger;

        PathPatternParser parser = new PathPatternParser();
        this.excludedPaths = properties.excludePaths().stream()
                .map(parser::parse)
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        return excludedPaths.stream().anyMatch(pattern -> pattern.matches(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = resolveCorrelationId(request);
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        // Step 1: buffer the body if we are going to log it. Outside the guard
        // below on purpose — if reading the stream fails, the request is already
        // unusable and pretending otherwise just moves the error somewhere less
        // obvious.
        HttpServletRequest effectiveRequest = request;
        byte[] bufferedBody = null;
        if (shouldBufferBody(request)) {
            RepeatableBodyRequestWrapper buffered = new RepeatableBodyRequestWrapper(request);
            effectiveRequest = buffered;
            bufferedBody = buffered.body;
        }

        // Step 2: emit the request line. This now runs BEFORE the handler, so it
        // needs its own fail-open guard — without one, a defect in logging would
        // break the request outright rather than merely lose a log line, which is
        // strictly worse than not logging at all.
        try {
            trafficLogger.logRequest(new LoggedHttpRequest(
                    HttpDirection.INCOMING,
                    correlationId,
                    request.getMethod(),
                    fullUri(request),
                    requestHeaders(request),
                    requestBody(request, bufferedBody)));
        } catch (Exception loggingFailure) {
            log.warn("HTTP request logging failed for {} {}",
                    request.getMethod(), request.getRequestURI(), loggingFailure);
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long startedAtNanos = System.nanoTime();
        try {
            filterChain.doFilter(effectiveRequest, wrappedResponse);
        } finally {
            try {
                trafficLogger.logResponse(new LoggedHttpResponse(
                        HttpDirection.INCOMING,
                        correlationId,
                        request.getMethod(),
                        fullUri(request),
                        wrappedResponse.getStatus(),
                        (System.nanoTime() - startedAtNanos) / 1_000_000L,
                        responseHeaders(wrappedResponse),
                        responseBody(wrappedResponse)));
            } catch (Exception loggingFailure) {
                // Fail open, always. A defect in logging must never turn a 200
                // into a 500 — the observer does not get to break the thing it
                // is observing.
                log.warn("HTTP response logging failed for {} {}",
                        request.getMethod(), request.getRequestURI(), loggingFailure);
            } finally {
                // Non-negotiable. ContentCachingResponseWrapper swallows the body
                // into its own buffer; without this copy the client receives an
                // empty response, which presents as a serialization bug and costs
                // an afternoon.
                wrappedResponse.copyBodyToResponse();

                // Also non-negotiable. Tomcat reuses threads, so an MDC entry left
                // behind attaches this request's id to whatever runs next on this
                // thread — log lines that are not merely unhelpful but actively
                // misleading.
                MDC.remove(CORRELATION_ID_MDC_KEY);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Request body buffering
    // ---------------------------------------------------------------------

    /**
     * Reads the body up front and replays it to whoever asks next.
     *
     * <h2>Why not ContentCachingRequestWrapper</h2>
     * That one caches <b>lazily, as downstream code reads</b>, so at the moment
     * the request line is emitted its cache is still empty
     * (spring-framework#28391, closed as working-as-designed). It suits a logger
     * that reports at the end of the exchange; it cannot serve one that reports
     * on arrival. Buffering eagerly and replaying does, and it removes the
     * drain-after-the-chain workaround the previous design needed.
     */
    private static final class RepeatableBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        private RepeatableBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Async reads are not supported here; nothing in this app uses them,
                    // and silently accepting a listener that never fires would be worse
                    // than refusing.
                    throw new UnsupportedOperationException("Async body reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /**
     * Whether it is safe <i>and</i> useful to buffer this request's body.
     *
     * <h2>JSON only — a correctness rule now, not just a security one</h2>
     * Form bodies are parsed into request parameters lazily by the container, on
     * the first {@code getParameter()} call. Reading the stream first means the
     * container never parses them and {@code getParameter("username")} returns
     * null — at idp-server, that is form login silently breaking. Because this
     * design reads eagerly, that rule stopped being about what we print and
     * became load-bearing for the request working at all. A form request is not
     * merely unlogged here; it is never wrapped, so it is untouched.
     * <p>
     * It closes a security hole in the same line, as before: the only form body
     * in this repo is idp-server's login POST, whose content is a plaintext
     * password.
     *
     * <h2>Why a declared length is required</h2>
     * Replaying a body means holding all of it, so a body we would refuse to
     * print is a body we should refuse to buffer. Requests with no declared
     * length (chunked) are passed through rather than read speculatively —
     * reading part of an over-large body would consume bytes we could not then
     * replay, corrupting the request to save a log line.
     */
    private boolean shouldBufferBody(HttpServletRequest request) {
        if (!properties.includeBodies() || !isJson(request.getContentType())) {
            return false;
        }
        long declaredLength = request.getContentLengthLong();
        return declaredLength > 0 && declaredLength <= properties.maxBodyBytes();
    }

    // ---------------------------------------------------------------------
    // Correlation id
    // ---------------------------------------------------------------------

    /**
     * Accepts an inbound id so a {@code curl} — or, later, an upstream service —
     * can name the request itself, otherwise generates one.
     *
     * <h2>The inbound value is attacker-controlled</h2>
     * It is a request header, so it goes through {@link #sanitize} before
     * reaching a log line. Writing a raw header into a log is <b>log injection</b>:
     * the value lands inside the {@code [...]} slot the format relies on, so a
     * caller sending {@code aaa] [FORGED} could otherwise fake the structure of
     * a line. Restricting to an identifier-shaped alphabet costs nothing and
     * closes it.
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        String inbound = request.getHeader(CORRELATION_ID_HEADER);
        return (inbound == null || inbound.isBlank()) ? newCorrelationId() : sanitize(inbound);
    }

    private static String sanitize(String candidate) {
        String cleaned = candidate.replaceAll("[^A-Za-z0-9_-]", "");
        if (cleaned.isEmpty()) {
            return newCorrelationId();
        }
        return cleaned.substring(0, Math.min(cleaned.length(), MAX_INBOUND_CORRELATION_ID_LENGTH));
    }

    /**
     * Eight hex characters, not a full UUID. This id exists to be read off a
     * terminal and eyeballed against another line; collision resistance across a
     * developer's afternoon is all it needs.
     */
    private static String newCorrelationId() {
        return UUID.randomUUID().toString().substring(0, CORRELATION_ID_LENGTH);
    }

    // ---------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------

    private static String fullUri(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + '?' + query;
    }

    private Map<String, List<String>> requestHeaders(HttpServletRequest request) {
        if (!properties.includeHeaders()) {
            return Map.of();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return masker.maskHeaders(headers);
    }

    private Map<String, List<String>> responseHeaders(ContentCachingResponseWrapper response) {
        if (!properties.includeHeaders()) {
            return Map.of();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.getHeaderNames().forEach(name -> headers.put(name, List.copyOf(response.getHeaders(name))));
        return masker.maskHeaders(headers);
    }

    private String requestBody(HttpServletRequest request, byte[] bufferedBody) {
        if (!properties.includeBodies()) {
            return null;
        }
        if (bufferedBody != null) {
            return render(bufferedBody);
        }
        return placeholder(request.getContentType(), request.getContentLengthLong());
    }

    private String responseBody(ContentCachingResponseWrapper response) {
        if (!properties.includeBodies()) {
            return null;
        }
        byte[] captured = response.getContentAsByteArray();
        if (captured.length == 0) {
            return null;
        }
        if (!isJson(response.getContentType())) {
            return placeholder(response.getContentType(), captured.length);
        }
        return render(captured);
    }

    /**
     * Masks first, truncates second — and the order is the point. A body cut
     * mid-JSON no longer parses, and a body that will not parse cannot be masked
     * at all, so truncating first would quietly disable masking on exactly the
     * large payloads most likely to contain something worth masking.
     */
    private String render(byte[] body) {
        String masked = masker.maskJsonBody(new String(body, StandardCharsets.UTF_8));
        if (masked == null || masked.length() <= properties.maxBodyBytes()) {
            return masked;
        }
        return masked.substring(0, properties.maxBodyBytes()) + "…<truncated>";
    }

    /** Describes a body we chose not to capture, rather than showing its content. */
    private static String placeholder(String contentType, long byteCount) {
        // No body at all — the common case for GET and DELETE. Note that a
        // bodyless request reports its length as -1 ("unknown"), not 0, so
        // testing for zero alone labels every GET as an uncaptured body.
        boolean hasNoBody = byteCount == 0 || (byteCount < 0 && contentType == null);
        if (hasNoBody) {
            return null;
        }
        return "<%s, %s bytes, not captured>".formatted(
                contentType == null ? "no content-type" : contentType,
                byteCount < 0 ? "unknown" : String.valueOf(byteCount));
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            // The suffix check catches application/problem+json, which Spring uses
            // for error responses and which is exactly what you want to read when
            // something has gone wrong.
            return mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || mediaType.getSubtype().endsWith("+json");
        } catch (Exception unparseableContentType) {
            return false;
        }
    }
}
