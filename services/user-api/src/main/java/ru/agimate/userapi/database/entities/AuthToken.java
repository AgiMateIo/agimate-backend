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
 * A one-time secret delivered by mail. Possession of it proves possession of the mailbox, which is
 * the same thing a provider vouches for with a verified address — and the only thing that may stand
 * between somebody and the password of an account.
 */
@Entity
@Table(name = "auth_tokens", uniqueConstraints = {
        @UniqueConstraint(name = "uq_auth_tokens_token_hash", columnNames = "token_hash")
})
@Getter
@Setter
public class AuthToken extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, columnDefinition = "TEXT")
    private AuthTokenPurpose purpose;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** SHA-256 of the token. The token itself exists in the letter and nowhere else. */
    @Column(name = "token_hash", nullable = false, columnDefinition = "TEXT")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Stamped when the token is spent, and only then. A newer token does not retire the ones already
     * in a mailbox: issuing can be triggered by anybody from an endpoint that needs no account, so
     * superseding would let a stranger kill a link its owner is holding. The lifetime bounds them.
     */
    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
