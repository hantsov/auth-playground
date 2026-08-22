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
 * Something we issued a person so they can prove who they are.
 * <p>
 * Splitting this off {@code users} is what lets a person hold several
 * authentication methods — or <b>none at all</b>. Before the split,
 * {@code users.password_hash} was {@code NOT NULL}, so every identity was
 * forced to have a password; a nullable column would have been no better, since
 * it conflates "no password yet" with "authenticates by other means".
 * <p>
 * Note the asymmetry this table is built around: it holds only <b>issued</b>
 * credentials. An inherent method like Smart-ID has no row here at all — the
 * state issued the identity, SK holds the key, and {@code users.national_id} is
 * the whole binding. See {@link UserCredentialType} for the full distinction
 * and the test to apply to any new method.
 * <p>
 * The {@code (type, identifier)} pair is unique, which makes credential lookup
 * a single indexed read.
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
     * The identity this credential proves. Many credentials, one person — and
     * legitimately zero, for someone who only ever uses an inherent method.
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
     * What the person presents to identify themselves under this method — the
     * login name, for {@code PASSWORD}.
     * <p>
     * Deliberately not {@code users.username}, even though the two are equal
     * for seeded users: the handle on the person row is for display and may
     * change freely, while this is the login key and does not.
     */
    @Column(name = "identifier", nullable = false, length = 255)
    private String identifier;

    /**
     * BCrypt hash for {@code PASSWORD}.
     * <p>
     * Nullable in the schema so a future issued method with something other
     * than a comparable secret has somewhere to go — but the
     * {@code password_requires_secret} CHECK constraint means a PASSWORD row
     * can never exist without one. The rule lives in the database rather than
     * here because a constraint holds for every writer, not just this class.
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
     * Revoking one issued method is not the same act as disabling a person,
     * and conflating them means you cannot do the first without the second.
     * <p>
     * There is no equivalent lever for an inherent method, and that is correct
     * rather than a gap: we did not issue the certificate and we cannot revoke
     * it. SK does that, and the certificate-chain and OCSP checks catch it.
     * Locally the levers are {@code users.enabled} or clearing the identifier.
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
