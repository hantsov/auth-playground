package ee.authplayground.resourceserver.appcore.security.authprincipal;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @CurrentUser AuthenticatedUser} controller parameters.
 * <p>
 * This is the single place that knows how the current auth mechanism maps
 * into the application's {@link AuthenticatedUser}. Today the principal is
 * a Keycloak-issued {@link Jwt}; the day the app moves to session-based
 * auth, only this class changes.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && AuthenticatedUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException(
                    "@CurrentUser requires an authenticated JWT principal");
        }
        return fromJwt(jwt);
    }

    private static AuthenticatedUser fromJwt(Jwt jwt) {
        return new AuthenticatedUser(
                jwt.getSubject(),
                jwt.getClaim("preferred_username"),
                jwt.getClaim("email"),
                jwt.getClaim("given_name"),
                jwt.getClaim("family_name")
        );
    }
}
