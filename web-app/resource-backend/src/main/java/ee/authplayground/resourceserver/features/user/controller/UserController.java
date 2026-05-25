package ee.authplayground.resourceserver.features.user.controller;

import ee.authplayground.resourceserver.appcore.security.authprincipal.AuthenticatedUser;
import ee.authplayground.resourceserver.appcore.security.authprincipal.CurrentUser;
import ee.authplayground.resourceserver.features.user.command.RegisterUser;
import ee.authplayground.resourceserver.features.user.command.SyncUser;
import ee.authplayground.resourceserver.features.user.entity.UserData;
import ee.authplayground.resourceserver.features.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 *   <li>{@code POST /api/user/register} — provision row from current identity</li>
 *   <li>{@code POST /api/user/sync}     — mirror current identity into row</li>
 * </ul>
 * <p>
 * No request bodies — the authenticated user (resolved by
 * {@link CurrentUser}) carries all the data we need.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<UserData> get(@CurrentUser AuthenticatedUser user) {
        return userService.findCurrent(user.subject())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not registered"));
    }

    @PostMapping("/register")
    public ResponseEntity<UserData> register(@CurrentUser AuthenticatedUser user) {
        RegisterUser cmd = toRegisterCommand(user);
        UserData created = userService.register(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/sync")
    public ResponseEntity<UserData> sync(@CurrentUser AuthenticatedUser user) {
        SyncUser cmd = new SyncUser(
                user.subject(),
                user.username(),
                user.email(),
                user.firstName(),
                user.lastName()
        );
        return ResponseEntity.ok(userService.sync(cmd));
    }

    private static RegisterUser toRegisterCommand(AuthenticatedUser user) {
        try {
            return new RegisterUser(
                    user.subject(),
                    user.username(),
                    user.email(),
                    user.firstName(),
                    user.lastName()
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
