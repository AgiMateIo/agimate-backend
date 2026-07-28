package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The «this connection is available to this agent» binding — M:N between {@code agents} and
 * {@code connections}. It is an <b>availability gate</b>: with no active row the connector is
 * unavailable to the agent (even when the {@code connections} record exists). For internal
 * connectors a connection is a mode row, one per user: all of that user's agents using the connector
 * point at it, and the data owner is resolved by the connector's code from {@code ConnectorEnv}.
 * Bindings of internal connectors are managed by the skill sync ({@code AgentSkillPolicyService})
 * and by the channel services; external ones (telegram/mcp/app) are explicit.
 *
 * <p>Tools are allowed by default once a binding exists; {@link AgentConnectionPolicy} only refines
 * that (DENY of specific ones, an allow-list via a wildcard, {@code params_filter}).
 *
 * <p>Uniqueness among active rows: {@code (agent_id, connection_id) WHERE deleted_at IS NULL} — the
 * partial unique index {@code uq_agent_connections_active} (JPA {@code @UniqueConstraint} cannot
 * express a partial one).
 */
@Entity
@Table(name = "agent_connections")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConnection extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return !isDeleted();
    }
}
