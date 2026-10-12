package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.McpOAuthTokenKind;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A code, access or refresh token of a {@link McpOAuthGrant}, in the AGM key format: only the key id
 * and the hash of the secret are stored.
 */
@Entity
@Table(name = "mcp_oauth_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uq_mcp_oauth_tokens_key_id", columnNames = "key_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpOAuthToken extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "grant_id", nullable = false)
    private UUID grantId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** When the token was replaced — a code exchanged, a refresh rotated; null while it is current. */
    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "TEXT")
    private McpOAuthTokenKind kind;

    @Column(name = "key_id", nullable = false, columnDefinition = "TEXT")
    private String keyId;

    @Column(name = "key_hash", nullable = false, columnDefinition = "TEXT")
    private String keyHash;
}
