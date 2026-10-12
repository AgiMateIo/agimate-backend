package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.McpOAuthClientKind;
import ru.agimate.controlapi.database.enums.McpOAuthGrantStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One consent on the MCP server: a user let a client act as one of their agents. Created per
 * consent rather than per {@code (agent, client_id)} — two machines running the same client share
 * a client_id (docs/decisions/mcp-server-oauth.md).
 */
@Entity
@Table(name = "mcp_oauth_grants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpOAuthGrant extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    private McpOAuthGrantStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_kind", nullable = false, columnDefinition = "TEXT")
    private McpOAuthClientKind clientKind;

    @Column(name = "client_id", nullable = false, columnDefinition = "TEXT")
    private String clientId;

    /** As the user saw it on the consent page; the client's document may rename itself later. */
    @Column(name = "client_name", nullable = false, columnDefinition = "TEXT")
    private String clientName;

    /** The exact redirect_uri of the authorization request, loopback port included. */
    @Column(name = "redirect_uri", nullable = false, columnDefinition = "TEXT")
    private String redirectUri;

    @Column(name = "resource", nullable = false, columnDefinition = "TEXT")
    private String resource;

    @Column(name = "code_challenge", nullable = false, columnDefinition = "TEXT")
    private String codeChallenge;
}
