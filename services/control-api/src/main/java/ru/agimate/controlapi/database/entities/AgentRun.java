package ru.agimate.controlapi.database.entities;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.RunStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "agent_runs", uniqueConstraints =
        @UniqueConstraint(columnNames = {"trigger_log_id", "agent_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_log_id", nullable = false)
    private TriggerLog triggerLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(name = "destination", nullable = false, columnDefinition = "TEXT")
    private String destination;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /**
     * Channel session this run writes to, or {@code null} for non-channel runs
     * (e.g. WEBHOOK/CENTRIFUGO delivery). Set by the backend at trigger routing.
     */
    @Column(name = "session_id")
    private UUID sessionId;

    /**
     * Run lifecycle — a projection of the run's {@code SaveMessage} stream (INBOUND → RUNNING,
     * ANSWER → DONE, ERROR → FAILED), observability only. Single-writer-per-session is enforced
     * by the partitioned {@code agent_exec} queue (a contract requirement on the transport),
     * not by this column.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private RunStatus status = RunStatus.ENQUEUED;

    /**
     * The run's latest sign of life: extended by its own RPCs (SaveMessage, GetLlmCredentials,
     * ExecuteToolAsync/GetToolResult). A RUNNING run idle for longer than the threshold is collected
     * by the background sweeper ({@code RunActivityService}).
     */
    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    /**
     * Snapshot of the route's channels ({@code Channels}: prompt/progress/answer), fixed at dispatch
     * time. Stored as a raw JSONB map so the entity layer does not depend on service types; typing is
     * the service layer's job ({@code TriggerRouterService} writes, {@code RunContextService} reads).
     * {@code null} — a direct run with no channels.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channels", columnDefinition = "JSONB")
    private Map<String, Object> channels;

    /**
     * Snapshot of the run's starting prompt: the message list exactly as it went into the first LLM
     * call (system + history + trigger with its ephemeral prefix). Written once by the worker before
     * the loop ({@code SavePrompt}), first-write-wins. Stored as an opaque JSON tree — observability,
     * not a projection; later turns of the run go to {@code agent_run_turns}. {@code null} — the
     * snapshot was never taken (the run did not reach the loop) or the run predates this feature.
     * User content → before production this falls under per-user DEK + retention, like
     * {@code agent_run_turns}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt", columnDefinition = "JSONB")
    private JsonNode prompt;
}
