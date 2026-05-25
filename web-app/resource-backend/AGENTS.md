# AGENTS.md — resource-backend

Conventions for AI coding assistants working in `web-app/resource-backend`.

> Cross-cutting project rules (package layout, pedagogy, versions) live in the [root AGENTS.md](../../AGENTS.md). This file holds conventions specific to this Spring Boot app. App overview and run instructions: [README.md](README.md).

## Controller / service boundary

The service layer is framework-agnostic. **No transport or auth-framework types pass into a service method.** That means no `Jwt`, no `Authentication`, no `HttpServletRequest`, no `OAuth2User`, no `Principal`. Services accept plain Java: identifiers, command records, domain types.

The translation happens at the controller boundary.

- **Identity** is resolved via `@CurrentUser AuthenticatedUser user` ([authprincipal](src/main/java/ee/authplayground/resourceserver/appcore/security/authprincipal/)). `AuthenticatedUser` is the app's framework-agnostic principal — it has no `Jwt` field, no `raw()` escape hatch, no Spring Security types. The day this app moves from JWT to sessions, only `CurrentUserArgumentResolver` changes.
- **If you genuinely need an obscure JWT claim** in one endpoint: that endpoint takes `@AuthenticationPrincipal Jwt jwt` *explicitly*. The coupling is visible in the signature, greppable, flagged by the compiler the day you migrate. Don't hide it inside `AuthenticatedUser`.

### The principal type stays small

`AuthenticatedUser` carries `subject` (always present) plus identity fields populated when the auth source supplies them. **Don't add fields just because the JWT happens to have them today.** Add a field only when downstream code needs it — and prefer adding it to a command/query type if the need is localized.

## Naming

### Descriptive over short for parameters

Service and controller method parameters favor explicit names that re-state the role, not three-letter abbreviations. Use `registerUserCmd`, not `cmd`. Use `userSubject`, not `s` or `id`. The redundancy with the type is intentional — it makes method bodies and log lines readable without the signature in view.

### Annotations describe source/behavior; types describe shape

Annotation names and the type they decorate serve different grammatical roles and should *not* match. `@CurrentUser AuthenticatedUser user` is correct: the annotation says *how to resolve this parameter*, the type says *what value you get*. `@AuthenticatedUser AuthenticatedUser user` is wrong — it stutters and conflates the two concepts.

The same principle applies elsewhere: a `@MockUser` test fixture annotation that produces an `AuthenticatedUser` is fine; a `@MockAuthenticatedUser` is noise.

### Package and config names are specific, not generic

Packages and configuration classes are named for *purpose*, not for *mechanism* or vague category.

- `appcore.security.authprincipal` (specific) — not `appcore.security.auth` (vague).
- `CurrentUserResolverConfig` (purpose) — not `WebMvcAuthConfig` (the mechanism it implements is `WebMvcConfigurer`, which is incidental).

If a name could apply to half a dozen unrelated things, it's too generic.

## Service signatures: commands for writes, primitives for reads

Influenced by CQRS:

- **Writes take command records.** `UserService.register(RegisterUser cmd)`, not `register(String subject, String username, String email, ...)` and not `register(Jwt jwt)`. Commands live under `features.<domain>.command`.
- **Reads take plain identifiers.** `UserService.findCurrent(String subject)`, not a command object. The asymmetry is correct — don't force a command shape onto the read path for symmetry.
- **Validation lives in the command's compact constructor.** `RegisterUser` throws `IllegalArgumentException` when `subject` or `username` is blank. The service never sees an invalid command. The controller catches the `IllegalArgumentException` at the boundary and translates to HTTP 400.
- **Build commands at the controller, not in the service.** The controller is where transport-shaped data (the resolved `AuthenticatedUser`, request bodies) gets translated into domain commands. Services that build commands from their own inputs are a smell — the boundary is wrong.

This keeps services testable without Spring Security or web context on the classpath, and documents each operation's required inputs in its parameter type.

## Concrete example: the user endpoints

End-to-end shape that follows all three conventions:

```java
// Controller — translates at the boundary
@PostMapping("/register")
public ResponseEntity<UserData> register(@CurrentUser AuthenticatedUser user) {
    RegisterUser registerUserCmd = new RegisterUser(
            user.subject(), user.username(), user.email(),
            user.firstName(), user.lastName());
    return ResponseEntity.status(CREATED).body(userService.register(registerUserCmd));
}

// Service — no Spring Security imports, takes a validated command
@Transactional
public UserData register(RegisterUser registerUserCmd) { ... }
```

See [UserController.java](src/main/java/ee/authplayground/resourceserver/features/user/controller/UserController.java) and [UserService.java](src/main/java/ee/authplayground/resourceserver/features/user/service/UserService.java) for the full pattern.
