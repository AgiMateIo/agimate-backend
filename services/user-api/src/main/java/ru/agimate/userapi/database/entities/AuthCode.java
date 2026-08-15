package ru.agimate.userapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A one-time code handed to a native client through the redirect and exchanged for tokens straight
 * afterwards. It is bound to the person, to the PKCE challenge of the app that started the login,
 * and to the address the redirect went to — a code intercepted on its way is worth nothing without
 * the verifier that only that app holds.
 */
@Entity
@Table(name = "auth_codes", uniqueConstraints = {
        @UniqueConstraint(name = "uq_auth_codes_code_hash", columnNames = "code_hash")
})
@Getter
@Setter
public class AuthCode extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** SHA-256 of the code. The code itself exists only in the redirect and in the app. */
    @Column(name = "code_hash", nullable = false, columnDefinition = "TEXT")
    private String codeHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_challenge", nullable = false, columnDefinition = "TEXT")
    private String codeChallenge;

    @Column(name = "redirect_uri", nullable = false, columnDefinition = "TEXT")
    private String redirectUri;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** The session the exchange created, so that a second exchange has something to revoke. */
    @Column(name = "session_id")
    private UUID sessionId;
}
