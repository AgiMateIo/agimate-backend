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
     * The session this run belongs to — the queue partition key and the single-writer scope. Resolved
     * at trigger routing: the parent's session for a run born of a run, then the channel's, then the
     * connection's. Never empty; whether the run has a <i>channel</i> is a different question, and it
     * is answered by {@code Channels.sessionIdOf(channels)}.
     */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /**
     * Run lifecycle — a projection of the run's {@code SaveMessage} stream (INBOUND → RUNNING,
     * ANSWER → DONE or CANCELLED, ERROR → FAILED), observability only. Single-writer-per-session is
     * enforced by the partitioned {@code agent_exec} queue (a contract requirement on the transport),
     * not by this column.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private RunStatus status = RunStatus.ENQUEUED;

    /**
     * When the user asked the run to stop. Apart from {@link #status} because cancellation is a request,
     * not a fact: the run stays RUNNING until it reaches a seam and reads it. Also settles the «cancel
     * against finish» race — a terminal ANSWER with this set lands as CANCELLED, without it as DONE.
     */
    @Column(name = "cancel_requested_at")
    private LocalDateTime cancelRequestedAt;

    /**
     * Steering claim: the running run of this session that took this run's inbound to absorb
     * mid-loop ({@code ClaimSteering}). A claim alone proves nothing — see {@link #steeredAt}.
     * The run itself stays in the DBOS queue throughout; nothing here re-enqueues or removes it.
     */
    @Column(name = "main_run_id")
    private UUID mainRunId;

    /**
     * When the main run confirmed the model actually saw this run's inbound ({@code MarkSteered},
     * sent after the LLM call following the absorption). Apart from {@link #mainRunId} because claim
     * and absorption fail differently: a lost claim response leaves this null and the run executes
     * normally (no message is ever lost); a lost confirmation costs a duplicate answer. The run
     * stands aside (→ {@code STEERED}) at its own seq 0 only when this is set <b>and</b> the main
     * finished DONE/CANCELLED — a FAILED main never answered the user, so the run executes.
     */
    @Column(name = "steered_at")
    private LocalDateTime steeredAt;

    /**
     * The run's latest sign of life: extended by its own RPCs (SaveMessage, GetLlmCredentials,
     * ExecuteToolAsync/GetToolResult, ClaimSteering). A RUNNING run idle for longer than the threshold
     * is collected by the background sweeper ({@code RunActivityService}).
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

    /**
     * Whether this run's turn ledger ({@code agent_run_turns}) can be replayed as history. Checked
     * once, when the run finishes, because {@code SaveTurn} is best-effort: a lost turn leaves a
     * {@code tool_use} with no {@code tool_result}, and providers reject such a request whole rather
     * than degrade. Orthogonal to {@link #status}: the status says the run finished, this says its
     * record is usable — history needs both.
     */
    @Column(name = "turns_intact", nullable = false)
    @Builder.Default
    private boolean turnsIntact = true;
}
