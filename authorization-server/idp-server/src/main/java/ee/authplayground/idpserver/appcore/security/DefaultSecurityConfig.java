package ee.authplayground.idpserver.appcore.security;

import ee.authplayground.idpserver.features.smartid.service.SmartIdAuthenticationProvider;
import ee.authplayground.idpserver.features.users.service.UserDataDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

/**
 * Owns every HTTP endpoint that isn't an OAuth2/OIDC protocol endpoint —
 * the Thymeleaf login form, its CSS/static assets, and the actuator
 * health check. Runs after {@link AuthorizationServerConfig}'s filter
 * chain because that one has HIGHEST_PRECEDENCE.
 * <p>
 * The login form lives at {@code /login}. Successful authentication
 * establishes an HTTP session — Spring Auth Server then reads that session
 * when the browser is bounced back to {@code /oauth2/authorize}, so the
 * user doesn't have to log in twice.
 */
@Configuration
public class DefaultSecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager
    ) throws Exception {
        http
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(authorize -> authorize
                        // Static assets and the login page itself are open.
                        .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/error").permitAll()
                        // The Smart-ID endpoints are part of logging in, so they
                        // cannot require being logged in. They are not unguarded:
                        // /start only causes a push notification to a phone whose
                        // owner must then approve it, and /status reveals nothing
                        // but the state of a session whose ID the caller already
                        // had.
                        .requestMatchers("/login/smart-id/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                // Custom Thymeleaf login page at /login. The same URL serves
                // both the form (GET, via LoginController) and the credentials
                // submission (POST, intercepted by Spring Security's form-login
                // filter). On failure Spring redirects to /login?error which
                // the template surfaces as a flash banner.
                .formLogin(form -> form
                        .loginPage("/login")
                        .permitAll()
                )
                // The Smart-ID endpoints are called by fetch() from the login
                // page, which cannot carry a CSRF token before there is a
                // session to carry it in. Exempting them is safe because neither
                // is a state-changing action on an existing session: /start
                // triggers a push the phone's owner must independently approve,
                // and /status only reports on a session ID the caller already
                // holds. The form-login POST keeps its CSRF protection.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/login/smart-id/**"))
                .requestCache(cache -> cache.requestCache(readOnlyRequestCache()));
        return http.build();
    }

    /**
     * Reads the saved request, but never writes one.
     *
     * <h2>The bug this fixes</h2>
     * There is exactly one request in this server worth resuming after login:
     * the {@code /oauth2/authorize} the browser arrived with. That one is saved
     * by the <i>authorization server</i> chain, which owns that endpoint.
     * <p>
     * This chain, meanwhile, requires authentication for <b>every unmatched
     * path</b> — so any stray request from the browser also gets saved, and the
     * last one wins. Chrome with DevTools open probes
     * {@code /.well-known/appspecific/com.chrome.devtools.json} on its own
     * initiative; that probe overwrote the pending authorization request, and
     * the first login after it redirected the user to a nonexistent JSON path
     * instead of back into the OAuth2 flow. Reopening the page appeared to
     * "fix" it only because the session was authenticated by then and never
     * consulted the cache again.
     * <p>
     * Favicon requests, prefetches and extension probes are all the same shape
     * of hazard, so the fix is to stop this chain saving anything rather than to
     * enumerate the offenders.
     *
     * <h2>Read-only, not disabled</h2>
     * A {@code NullRequestCache} would also stop the overwriting — and would
     * break password login, because form-login's success handler <i>reads</i>
     * this cache to find the authorization request the other chain saved.
     * Saving and reading are separate concerns here: only saving is harmful.
     *
     * <h2>Deliberately not a {@code @Bean}</h2>
     * Spring Security's {@code RequestCacheConfigurer} adopts a single
     * {@code RequestCache} bean for <b>every</b> filter chain. Exposing this one
     * would apply "never save" to the authorization server chain as well, which
     * is the chain that has to save. It stays a local instance so its reach is
     * exactly this chain.
     */
    private RequestCache readOnlyRequestCache() {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(request -> false);
        return requestCache;
    }

    /**
     * One {@link AuthenticationManager}, two providers — which is the whole
     * shape of this phase in a single bean.
     *
     * <h2>Why both live in one manager</h2>
     * Password and Smart-ID are two ways to establish the same thing, and they
     * converge on the same principal type and the same {@code sub}. Giving each
     * its own manager would make them look like two systems that happen to
     * coexist; one manager with two providers says what is actually true — a
     * single authentication service that accepts more than one kind of proof.
     * <p>
     * They differ in what they consult, and that difference is the design:
     * {@link DaoAuthenticationProvider} fetches an <i>issued</i> credential and
     * compares a secret, while the Smart-ID provider looks up a <i>person</i>
     * because there is no secret of ours involved anywhere in that method.
     *
     * <h2>Declared explicitly rather than left to auto-configuration</h2>
     * Boot would build a manager for form login on its own, and it would not
     * know about Smart-ID. Naming both providers here is also the only place a
     * reader can see the complete list of ways into this server.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            UserDataDetailsService userDataDetailsService,
            PasswordEncoder passwordEncoder,
            SmartIdAuthenticationProvider smartIdAuthenticationProvider
    ) {
        DaoAuthenticationProvider passwordProvider = new DaoAuthenticationProvider(userDataDetailsService);
        passwordProvider.setPasswordEncoder(passwordEncoder);

        return new ProviderManager(passwordProvider, smartIdAuthenticationProvider);
    }
}
