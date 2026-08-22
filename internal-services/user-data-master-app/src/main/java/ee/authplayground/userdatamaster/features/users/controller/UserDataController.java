package ee.authplayground.userdatamaster.features.users.controller;

import ee.authplayground.userdatamaster.features.users.dto.UserDataResponse;
import ee.authplayground.userdatamaster.features.users.repository.UserDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Person attributes for services that need them.
 * <p>
 * Neither endpoint here is on the Phase 1 login path — idp-server gets
 * everything it needs from the credential lookup. They exist now because
 * {@code customer:read} needs something to read, and because Phase 2's
 * registration flow and resource-backend both consume them.
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
@Slf4j
public class UserDataController {

    private final UserDataRepository userDataRepository;

    /**
     * By {@code sub}. This is the only identifier a consumer should ever key on
     * — OIDC Core 5.7 makes {@code sub} + {@code iss} the only claims a relying
     * party may rely on as stable, and it is the only field on the row that is
     * stable by construction.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public ResponseEntity<UserDataResponse> getById(@PathVariable UUID id) {
        log.debug("User lookup by id: {}", id);

        return userDataRepository.findById(id)
                .map(UserDataResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No user with id " + id));
    }

    /**
     * By national ID — <b>the resolution step for every inherent authentication
     * method</b>, and Phase 2's registration lookup besides.
     * <p>
     * This is the counterpart to {@code UserCredentialController}'s credential
     * lookup, for methods that have no credential row. Smart-ID authentication
     * ends here: the certificate's subject DN yields the ETSI semantics
     * identifier, idp-server splits it into country + code, and this call
     * answers "which person is that, and do we know them?" A hit resolves to
     * {@code users.id} and becomes the {@code sub}; a miss is Phase 2B's
     * registration trigger.
     * <p>
     * That puts it on the login hot path, not merely in a registration flow.
     * <p>
     * Takes <b>both halves</b> of the identity, and the second is not optional
     * politeness: national ID numbers are unique within a country, not globally.
     * Smart-ID's own demo set has {@code PNOEE-40404040009} and
     * {@code PNOLT-40404040009} as different people sharing a number. An
     * endpoint taking the bare code would happily return the wrong human.
     */
    @GetMapping("/by-national-id/{nationalId}")
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public ResponseEntity<UserDataResponse> getByNationalId(
            @PathVariable String nationalId,
            @RequestParam String nationality
    ) {
        log.debug("User lookup by national id: {}-{}", nationality, nationalId);

        return userDataRepository.findByNationalityAndNationalId(nationality, nationalId)
                .map(UserDataResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No user with national id " + nationality + "-" + nationalId));
    }
}
