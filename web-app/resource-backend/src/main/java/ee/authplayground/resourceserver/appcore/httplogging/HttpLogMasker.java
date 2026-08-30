package ee.authplayground.resourceserver.appcore.httplogging;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes sensitive values from captured traffic, per
 * {@link HttpLoggingProperties.Masking}.
 *
 * <h2>Knows about secrets, knows nothing about transport</h2>
 * No servlet types, no client types, no Spring. It takes headers and a body and
 * returns masked copies, which is what lets the incoming filter and the outgoing
 * interceptor share one masking policy — and what makes it testable without a
 * web context.
 *
 * <h2>Everything here is off by default</h2>
 * See {@link HttpLoggingProperties} for why a playground inverts the production
 * default. With defaults, every method here is a pass-through.
 */
@Slf4j
public class HttpLogMasker {

    static final String MASK = "***";

    /**
     * {@code Proxy-Authorization} is included because it carries exactly the same
     * kind of value and gets forgotten exactly as often.
     */
    private static final Set<String> AUTH_HEADERS = Set.of("authorization", "proxy-authorization");

    private static final Set<String> COOKIE_HEADERS = Set.of("cookie", "set-cookie");

    private final HttpLoggingProperties.Masking masking;
    private final ObjectMapper objectMapper;

    public HttpLogMasker(HttpLoggingProperties.Masking masking, ObjectMapper objectMapper) {
        this.masking = masking;
        this.objectMapper = objectMapper;
    }

    /**
     * Header names are case-insensitive per RFC 9110, so matching is too — a
     * client sending {@code authorization} in lower case must not slip past a
     * denylist written in title case.
     */
    public Map<String, List<String>> maskHeaders(Map<String, List<String>> headers) {
        if (!masking.authTokens() && !masking.cookies()) {
            return headers;
        }

        Map<String, List<String>> masked = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String lowerCaseName = name.toLowerCase(Locale.ROOT);
            boolean maskThis = (masking.authTokens() && AUTH_HEADERS.contains(lowerCaseName))
                    || (masking.cookies() && COOKIE_HEADERS.contains(lowerCaseName));
            masked.put(name, maskThis ? List.of(MASK) : values);
        });
        return masked;
    }

    /**
     * Replaces configured field names wherever they occur in a JSON document,
     * at any depth.
     *
     * <h2>Parsed, not pattern-matched</h2>
     * Regex over JSON is how a masker eventually misses a value and prints a
     * password hash — an escaped quote, a field name appearing inside a string,
     * a line break in the wrong place. Parsing costs an allocation and is
     * correct.
     *
     * <h2>Fail open, deliberately</h2>
     * A body that will not parse is returned <b>unchanged</b> rather than
     * withheld. That is the right call in a playground whose defaults do not
     * mask anything anyway — but it is worth stating plainly, because in a
     * system that masks for real the safe failure is the opposite one: withhold
     * the body and log that masking failed.
     */
    public String maskJsonBody(String body) {
        if (body == null || body.isBlank() || masking.jsonFields().isEmpty()) {
            return body;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            maskInPlace(root, Set.copyOf(masking.jsonFields()));
            return objectMapper.writeValueAsString(root);
        } catch (Exception notJsonOrUnwritable) {
            log.debug("Body could not be parsed as JSON for masking; logging it unchanged", notJsonOrUnwritable);
            return body;
        }
    }

    private void maskInPlace(JsonNode node, Set<String> fieldsToMask) {
        if (node instanceof ObjectNode objectNode) {
            // Property names are collected first: replacing values while iterating
            // the live property set is a concurrent modification waiting to happen.
            List<String> propertyNames = objectNode.properties().stream()
                    .map(Map.Entry::getKey)
                    .toList();

            for (String propertyName : propertyNames) {
                if (fieldsToMask.contains(propertyName)) {
                    objectNode.put(propertyName, MASK);
                } else {
                    maskInPlace(objectNode.get(propertyName), fieldsToMask);
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(element -> maskInPlace(element, fieldsToMask));
        }
        // Value nodes are terminal: a match is decided by the name its parent
        // holds it under, which the ObjectNode branch above has already checked.
    }
}
