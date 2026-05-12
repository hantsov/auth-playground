package ee.authplayground.resourceserver.features.user.controller;

import ee.authplayground.resourceserver.features.user.entity.UserData;
import ee.authplayground.resourceserver.features.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * UserController — endpoints for the authenticated user's own record.
 * <p>
 * Three endpoints, each with one responsibility, intentionally split so the
 * SPA's account bootstrap reads cleanly: register on the first hit, sync on
 * subsequent logins, plain read everywhere else.
 *
 * <ul>
 *   <li>{@code GET  /api/user}          — read; 404 if not yet provisioned</li>
 *   <li>{@code POST /api/user/register} — provision row from JWT claims</li>
 *   <li>{@code POST /api/user/sync}     — mirror current JWT claims into row</li>
 * </ul>
 * <p>
 * No request bodies — the validated JWT (injected as the
 * {@link Authentication} principal by Spring Security's resource server
 * filter) carries all the data we need.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<UserData> get(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return userService.findCurrent(jwt)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not registered"));
    }

    @PostMapping("/register")
    public ResponseEntity<UserData> register(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        UserData created = userService.register(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/sync")
    public ResponseEntity<UserData> sync(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return ResponseEntity.ok(userService.syncFromJwt(jwt));
    }
}
