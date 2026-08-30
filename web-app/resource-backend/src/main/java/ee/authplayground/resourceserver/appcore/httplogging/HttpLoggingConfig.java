package ee.authplayground.resourceserver.appcore.httplogging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires HTTP traffic logging.
 *
 * <p>Every collaborator is constructed here rather than component-scanned, so
 * the whole feature can be understood by reading one file — and so the filter's
 * position in the chain is stated explicitly rather than inferred.
 */
@Configuration
@EnableConfigurationProperties(HttpLoggingProperties.class)
public class HttpLoggingConfig {

    /**
     * One step ahead of Spring Security's chain, so rejected requests are logged.
     *
     * <p>Derived from the constant rather than written as {@code -101}: the value
     * is stable, but deriving it says <i>why</i> this number.
     *
     * <p>Note the constant moved in Boot 4 — it used to live on
     * {@code SecurityProperties}, which the autoconfigure module split reduced to
     * just the {@code user} block. Filter wiring now has its own properties type.
     */
    private static final int BEFORE_SPRING_SECURITY = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1;

    @Bean
    public HttpLogMasker httpLogMasker(HttpLoggingProperties properties, ObjectMapper objectMapper) {
        return new HttpLogMasker(properties.masking(), objectMapper);
    }

    @Bean
    public HttpTrafficLogger httpExchangeLogger() {
        return new HttpTrafficLogger();
    }

    /**
     * <b>The filter is constructed here and is deliberately not a {@code @Component}.</b>
     * Boot auto-registers any bean of type {@code Filter} into the servlet chain,
     * so a filter that is both annotated and registered here runs twice and logs
     * every request twice — with no error to point at.
     */
    @Bean
    public FilterRegistrationBean<IncomingHttpLoggingFilter> incomingHttpLoggingFilter(
            HttpLoggingProperties properties,
            HttpLogMasker masker,
            HttpTrafficLogger exchangeLogger) {

        FilterRegistrationBean<IncomingHttpLoggingFilter> registration =
                new FilterRegistrationBean<>(new IncomingHttpLoggingFilter(properties, masker, exchangeLogger));

        registration.setOrder(BEFORE_SPRING_SECURITY);
        registration.addUrlPatterns("/*");
        registration.setName("incomingHttpLoggingFilter");
        return registration;
    }
}
