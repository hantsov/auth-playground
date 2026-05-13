package ee.authplayground.idpserver.appcore.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

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
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        // Static assets and the login page itself are open.
                        .requestMatchers("/login", "/css/**", "/images/**", "/error").permitAll()
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
                );
        return http.build();
    }
}
