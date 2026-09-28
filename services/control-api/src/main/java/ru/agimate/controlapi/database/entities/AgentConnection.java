package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The «this connection is available to this agent» binding — M:N between {@code agents} and
 * {@code connections}. It is an <b>availability gate</b>: with no active row the connector is
 * unavailable to the agent (even when the {@code connections} record exists). For internal
 * connectors a connection is a mode row, one per user: all of that user's agents using the connector
 * point at it, and the data owner is resolved by the connector's code from {@code ConnectorEnv}.
 * Every binding is the user's explicit decision — internal connectors included (they are addressed by
 * code, their mode row is materialised on the spot). Skills do not create or remove bindings: they
 * declare which instance they work with and go unsatisfied while it is not open. The exception is a
 * channel (webchat/acp), which creates its binding together with itself.
 *
 * <p>Tools are allowed by default once a binding exists; {@link AgentConnectionPolicy} only refines
 * that (DENY of specific ones, an allow-list via a wildcard, {@code params_filter}).
 *
 * <p>Uniqueness among active rows: {@code (agent_id, connection_id) WHERE deleted_at IS NULL} — the
 * partial unique index {@code uq_agent_connections_agent_id_connection_id_active} (JPA {@code @UniqueConstraint} cannot
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

    /**
     * The connector's settings for this agent, in the shape of a record the connector declares; read
     * and written only through {@code ConnectorSettingsService}. A rebind is a new row, so it starts empty.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnDefault("'{}'::jsonb")
    @Column(name = "settings", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private Map<String, Object> settings = new HashMap<>();

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return !isDeleted();
    }
}
