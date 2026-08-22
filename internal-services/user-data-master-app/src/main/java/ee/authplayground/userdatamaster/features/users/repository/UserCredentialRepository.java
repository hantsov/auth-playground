package ee.authplayground.userdatamaster.features.users.repository;

import ee.authplayground.userdatamaster.features.users.entity.UserCredential;
import ee.authplayground.userdatamaster.features.users.entity.UserCredentialType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {

    /**
     * The login-path query — one indexed read against {@code UNIQUE (type, identifier)}.
     * <p>
     * {@code JOIN FETCH} rather than a lazy load: the caller always needs the
     * person too (the response carries both), and this is the hot path for
     * every login in the system. One query, not two.
     */
    @Query("""
            SELECT c FROM UserCredential c
            JOIN FETCH c.user
            WHERE c.type = :type AND c.identifier = :identifier
            """)
    Optional<UserCredential> findByTypeAndIdentifierWithUser(UserCredentialType type, String identifier);

    boolean existsByTypeAndIdentifier(UserCredentialType type, String identifier);
}
