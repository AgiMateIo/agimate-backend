package ru.agimate.userapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One sign-in on one device. This is the registry that makes a logout an actual revocation: the
 * refresh token is only accepted while the row still names its {@code jti} and has not been
 * revoked, which an in-memory list could never guarantee across replicas or restarts.
 */
@Entity
@Table(name = "auth_sessions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_auth_sessions_current_jti", columnNames = "current_jti")
})
@Getter
@Setter
public class AuthSession extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "client", nullable = false, columnDefinition = "TEXT")
    private AuthClient client;

    @Column(name = "device_label", columnDefinition = "TEXT")
    private String deviceLabel;

    /** The only refresh id that mints a new pair outright. */
    @Column(name = "current_jti", nullable = false, columnDefinition = "TEXT")
    private String currentJti;

    /**
     * The id {@link #currentJti} replaced. Accepted for a short while after {@link #rotatedAt} so a
     * refresh whose response never arrived can be retried; accepted later, it means the token is in
     * two hands and the session is revoked instead.
     */
    @Column(name = "previous_jti", columnDefinition = "TEXT")
    private String previousJti;

    /** When {@link #previousJti} was replaced; null until the first rotation. */
    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** Moves forward on every rotation: a session dies of disuse, not of age. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", columnDefinition = "TEXT")
    private SessionRevokeReason revokeReason;
}
