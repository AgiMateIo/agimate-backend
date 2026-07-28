package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobStatus;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Registry of connector background jobs — the source of truth for the pull-based scheduler
 * {@code ConnectorJobScheduler}.
 *
 * <p>The executable code itself is not stored in the database: {@code name} is dispatched to a
 * {@code @Tool} method of the connector's tool service with the arguments {@link #args}.
 * {@link #config} holds schedule parameters only ({@code intervalSeconds}, {@code cron},
 * {@code zone}).
 *
 * <p>Uniqueness of the business key {@code (connector_code, connection_id, name)} applies to
 * {@code kind = SYSTEM} only (a partial unique index, see
 * {@code 2026/06/11-01-connector-jobs-kind-paused.xml}; JPA {@code @UniqueConstraint} cannot do
 * partial) — that is the invariant of the reconcile sync ({@code findByBusinessKey} returns an
 * {@code Optional}). USER/AGENT rows are identified by their own {@code id}, and there may be many
 * of them per {@code name}.
 */
@Entity
@Table(name = "connector_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorJob extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    /**
     * Identifier of the connector instance: {@code connections.id} as a string (as in
     * {@code ToolCallLog}); for dynamic jobs it is the connection_id of the initiating tool call
     * (restored into {@code ConnectorEnv} when the job fires); {@code null} when no instance applies.
     */
    @Column(name = "connection_id", columnDefinition = "TEXT")
    private String connectionId;

    /** Owner of the job: the user who created the integration, or the owner of the initiating agent. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * The initiating agent for dynamic jobs (e.g. {@code time.schedule}): used for list/cancel and to
     * reconstruct {@link ru.agimate.controlapi.connectors.core.ConnectorEnv} when the job fires.
     * {@code null} for declarative integration jobs.
     */
    @Column(name = "agent_id")
    private UUID agentId;

    /**
     * The initiating agent's originating channel (a snapshot taken at scheduling time): where a
     * dynamic job addresses its answer. Reconstructed into {@code ConnectorEnv.channelId} when the
     * job fires. {@code null} when the job was scheduled outside a channel context.
     */
    @Column(name = "channel_id")
    private UUID channelId;

    /**
     * The initiating agent's prompt session (a snapshot taken at scheduling time, symmetric to
     * {@link #channelId}): when the job fires it is reconstructed into {@code ConnectorEnv.sessionId},
     * and the trigger producer puts it into the proactive {@code ChannelInfo} — the run then gets the
     * history and the partition of the original conversation for as long as the session lives.
     * {@code null} when the job was scheduled outside a channel context.
     */
    @Column(name = "session_id")
    private UUID sessionId;

    /** Row category — see {@link ConnectorJobKind}; it decides whether the business key applies. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "TEXT")
    private ConnectorJobKind kind;

    /** Paused by the user: while this is not {@code null} the scheduler does not pick the row up. */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    /** Job name; dispatched to the connector's {@code @Tool} method of the same name. */
    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "TEXT")
    private ConnectorJobType type;

    /** Schedule parameters: {@code intervalSeconds} | {@code cron}, {@code zone}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> config;

    /** Arguments passed into the method on every run. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "args", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> args;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private ConnectorJobStatus status = ConnectorJobStatus.PENDING;

    /** When the poller should pick the job up next; {@code null} for COMPLETED. */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    /** Limit of a single iteration in seconds: a claim sets {@code lease_until = now + timeout_seconds}. */
    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds;

    /** Until this moment the row counts as «taken» by the current node; after it, it is picked up again. */
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    /** When the scheduler last claimed the row (informational). */
    @Column(name = "last_started_at")
    private LocalDateTime lastStartedAt;

    /** The last observed error message (informational). */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
