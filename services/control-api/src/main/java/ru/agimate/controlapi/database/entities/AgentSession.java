package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.AgentSessionScope;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A stream of work with a single writer: the session of every run and the partition key of the
 * {@code agent_exec} queue (docs/decisions/agent-sessions.md).
 *
 * <p>Two invariants live in the schema and cannot be expressed here. The check
 * {@code (scope = 'CHANNEL') = (channel_id IS NOT NULL)}, and the partial unique index
 * {@code uq_agent_sessions_agent_id_connection_id_live} — at most one live session per {@code (agent_id,
 * connection_id)} among {@code CONNECTION} rows. There is deliberately no such index for
 * {@code CHANNEL}: a channel legitimately has many live sessions.
 */
@Entity
@Table(name = "agent_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, columnDefinition = "TEXT")
    private AgentSessionScope scope;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * The connector the session belongs to. Filled for channel sessions too — the channel carries it
     * anyway, and history across a connector must not need a join to {@code channels}.
     */
    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    /** The channel of a {@code CHANNEL} session; {@code null} for every other scope. */
    @Column(name = "channel_id")
    private UUID channelId;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    /**
     * Last time the session did anything: a channel message, or a trigger routed into it. Apart from
     * {@code updated_at}, which belongs to the row rather than to the conversation.
     */
    @Column(name = "last_activity_at", nullable = false)
    @Builder.Default
    private LocalDateTime lastActivityAt = LocalDateTime.now();

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
