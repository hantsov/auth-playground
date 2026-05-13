package ee.authplayground.idpserver.features.users.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The IdP's local user record.
 * <p>
 * The {@code id} is the stable subject identifier issued to Keycloak as
 * the {@code sub} claim. It is generated at user creation time and never
 * changes — username and email may be updated freely without breaking
 * the federated identity link Keycloak holds.
 * <p>
 * Passwords are stored as BCrypt hashes. Seed users come from
 * {@code UserSeedRunner} on first startup.
 */
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserData {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", unique = true, nullable = false, length = 100)
    private String username;

    @Column(name = "email", unique = true, nullable = false, length = 255)
    private String email;

    @Column(name = "given_name", length = 100)
    private String givenName;

    @Column(name = "family_name", length = 100)
    private String familyName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
