package ee.authplayground.userdatamaster.features.users.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One way a person can prove who they are.
 * <p>
 * Splitting this off {@code users} is what lets two authentication methods
 * resolve to one identity. Before the split, {@code users.password_hash} was
 * {@code NOT NULL} — there was no way to express "this human authenticates by
 * Smart-ID" without contorting the schema.
 * <p>
 * The {@code (type, identifier)} pair is unique, which makes credential lookup
 * a single indexed read for every method: password login and Smart-ID login
 * become the same query with a different {@code type}.
 */
@Entity
@Table(name = "user_credentials")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserCredential {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The identity this credential proves. Many credentials, one person — that
     * is the whole point of the table.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private UserData user;

    /**
     * Persisted as a string, not an ordinal. A reordered enum silently
     * rewriting the meaning of every row is a well-known way to lose a database.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private UserCredentialType type;

    /**
     * What the person presents to identify themselves under this method:
     * the login name for {@code PASSWORD}, the ETSI semantics identifier
     * ({@code PNOEE-40404040009}) for {@code SMART_ID}.
     */
    @Column(name = "identifier", nullable = false, length = 255)
    private String identifier;

    /**
     * BCrypt hash for {@code PASSWORD}; {@code null} for {@code SMART_ID},
     * which has no secret on our side at all.
     * <p>
     * Note this leaves the service on the wire, to exactly one caller — see
     * {@code UserCredentialController} for why the master hands out hashes
     * rather than verifying them itself.
     */
    @ToString.Exclude
    @Column(name = "secret_hash", length = 255)
    private String secretHash;

    /**
     * Per-credential, deliberately distinct from {@code users.enabled}.
     * Revoking one authentication method is not the same act as disabling a
     * person, and conflating them means you cannot do the first without the
     * second.
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        createdAt = LocalDateTime.now();
    }
}
