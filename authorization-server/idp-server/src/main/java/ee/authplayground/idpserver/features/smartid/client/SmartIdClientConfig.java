package ee.authplayground.idpserver.features.smartid.client;

import ee.authplayground.idpserver.features.smartid.SmartIdProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Builds the Smart-ID HTTP client and turns {@link SmartIdApi} into a live
 * proxy.
 *
 * <h2>No credentials on this client, and that is not an omission</h2>
 * Compare {@code UserMasterClientConfig}, where every call carries a bearer
 * token: SK authenticates relying parties by {@code relyingPartyUUID} plus a
 * source IP registered in advance, so there is no {@code Authorization} header
 * to attach. The demo UUID is published precisely because it is not a secret —
 * it is an identifier scoped to demo accounts, not a key.
 * <p>
 * Worth pausing on, because it is easy to read "no auth header" as "unfinished".
 * Production tightens this by registering the RP's source IP with SK, which is
 * infrastructure rather than code.
 */
@Configuration
@EnableConfigurationProperties(SmartIdProperties.class)
@Slf4j
public class SmartIdClientConfig {

    /**
     * Headroom over the long-poll timeout. The socket must outlive the poll SK
     * is holding open, or every successful long poll ends as a client-side read
     * timeout — a failure that looks like a network problem and is really an
     * arithmetic one.
     */
    private static final Duration READ_TIMEOUT_MARGIN = Duration.ofSeconds(15);

    @Bean
    public SmartIdApi smartIdApi(SmartIdProperties properties) {
        log.info("Smart-ID client -> {} (RP '{}', scheme '{}', certificate level {})",
                properties.baseUrl(),
                properties.relyingPartyName(),
                properties.schemeName(),
                properties.certificateLevel());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                // Status handling lives here rather than at each call site so the
                // interface stays a list of endpoints. The mapping is the point:
                // a 404 means two entirely different things depending on which
                // endpoint produced it, and collapsing them loses the distinction
                // between "not a Smart-ID user" and "your session expired".
                .defaultStatusHandler(status -> status.value() == 404, (request, response) -> {
                    String path = request.getURI().getPath();
                    if (path.contains("/session/")) {
                        throw new SmartIdSessionNotFoundException(
                                "Smart-ID session is unknown or has expired");
                    }
                    throw new SmartIdUserNotFoundException(
                            "No usable Smart-ID account for the requested identity");
                })
                .defaultStatusHandler(status -> status.value() != 404 && status.isError(),
                        (request, response) -> {
                            // Deliberately loud, and deliberately carrying the body.
                            // A 400 here is almost always a malformed request DTO —
                            // a wrong literal, a missing required field — and SK's
                            // problem+json says which. Swallowing it turns a
                            // five-second fix into an afternoon.
                            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new SmartIdUnavailableException(
                                    "Smart-ID returned %s for %s: %s".formatted(
                                            response.getStatusCode(), request.getURI().getPath(), body));
                        })
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(SmartIdApi.class);
    }

    private JdkClientHttpRequestFactory requestFactory(SmartIdProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(properties.pollTimeout().plus(READ_TIMEOUT_MARGIN));
        return factory;
    }
}
