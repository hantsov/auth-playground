package ee.authplayground.userdatamaster.features.users.entity;

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
 * A person. Identity and person attributes — no credential material.
 * <p>
 * The {@code id} is minted here and becomes the {@code sub} claim everywhere
 * downstream: idp-server asserts it, Keycloak links its shadow user to it,
 * resource-backend keys its rows on it. It is the only identifier in the system
 * that is stable by construction. Every other field on this entity is a mutable
 * display or contact attribute, and changing any of them breaks nothing.
 * <p>
 * That property is the entire reason this refactor happened: before it,
 * {@code sub} was the username, so renaming a user severed Keycloak's federated
 * identity link.
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

    /**
     * The bare national identity code, e.g. {@code 40404040009}.
     * <p>
     * Not the ETSI semantics identifier — that is
     * {@code "PNO" + nationality + "-" + nationalId} and lives on the
     * {@code SMART_ID} credential row, where it serves as a lookup index rather
     * than a person attribute. Same number, two roles, two places.
     */
    @Column(name = "national_id", length = 50)
    private String nationalId;

    /**
     * ISO 3166-1 alpha-2, e.g. {@code EE}. Supplies the country half of the
     * ETSI identifier, and makes {@link #nationalId} uniqueness meaningful:
     * national ID numbers are unique within a country, not globally.
     */
    @Column(name = "nationality", length = 2)
    private String nationality;

    /**
     * A display handle, free to change. Its former role as the login identifier
     * now belongs to {@code user_credentials.identifier} on the PASSWORD row.
     * <p>
     * Kept even though a PASSWORD credential duplicates it, because a
     * Smart-ID-only user has no PASSWORD row and still wants a name to show.
     */
    @Column(name = "username", unique = true, length = 100)
    private String username;

    /**
     * Contact attribute. <b>Never a join key</b> — see the schema comment in
     * {@code V1__init_user_master.sql} for why account-linking on a matching
     * email is an account-takeover primitive rather than a convenience.
     */
    @Column(name = "email", length = 255)
    private String email;

    /**
     * Whether anyone actually verified {@link #email}. Emitted as the
     * {@code email_verified} claim, which used to be a hardcoded {@code true}.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "given_name", length = 100)
    private String givenName;

    @Column(name = "family_name", length = 100)
    private String familyName;

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

    /**
     * The ETSI semantics identifier for this person — {@code PNOEE-40404040009}
     * — or {@code null} if either half is missing.
     * <p>
     * Derived rather than stored on this entity, because on {@code users} the
     * two halves are the person attributes and the combined form is a
     * convenience. Where it is a lookup key (the {@code SMART_ID} credential
     * row) it is stored, so that lookup stays a single indexed read.
     */
    public String toSemanticsIdentifier() {
        if (nationality == null || nationalId == null) {
            return null;
        }
        return "PNO" + nationality + "-" + nationalId;
    }
}
