package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.AgentTurnRole;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The canonical full-fidelity turn of a run: one record per worker AgentChatMessage
 * (inbound/assistant/tool), uncapped — unlike the capped channel projection
 * {@link ChannelSessionMessage}. This is the record the history of later runs is assembled from: the
 * projection answers «what did the user see», this one «what was actually said». Written for every
 * run, including direct ones ({@code session_id} = null), which is why the inbound turn belongs here:
 * a direct run has no channel history, so this is the only row-shaped record of what was asked.
 * Idempotency is UNIQUE (run_id, turn_index).
 *
 * <p>The USER row at {@code turn_index} 0 is the <b>persistent</b> part of the turn: the ephemeral
 * blocks (memory notes) that were prepended for the model alone stay out, and live in
 * {@code agent_runs.prompt} — that is the record of what the model saw, this one is the record of the
 * dialogue. SYSTEM turns are not written at all: static, large, and already in the prompt snapshot.
 */
@Entity
@Table(name = "agent_run_turns", uniqueConstraints =
        @UniqueConstraint(name = "uq_agent_run_turns_run_turn", columnNames = {"run_id", "turn_index"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunTurn extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** Denormalised continuity key (currently the channel's session, null for direct runs); AgentSession is deferred. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** Monotonic per-run turn counter: the idempotency key UNIQUE (run_id, turn_index). */
    @Column(name = "turn_index", nullable = false)
    private Integer turnIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, columnDefinition = "TEXT")
    private AgentTurnRole role;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    /** The assistant emitted reasoning on this turn (the 💭 marker). */
    @Column(name = "thinking", nullable = false)
    private boolean thinking;

    /**
     * The reasoning itself, uncapped; null when the model did not reason (then {@link #thinking} is
     * false too — one provider field feeds both). Kept here only: the channel projection carries the
     * marker, never the text.
     */
    @Column(name = "thinking_text", columnDefinition = "TEXT")
    private String thinkingText;

    /** Tool calls of an assistant turn ({@code [{id,name,argumentsJson}]}); null otherwise. Uncapped. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "JSONB")
    private List<Map<String, Object>> toolCalls;

    /** Tool results of a tool turn ({@code [{id,name,outputJson,failed}]}); null otherwise. Uncapped. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_results", columnDefinition = "JSONB")
    private List<Map<String, Object>> toolResults;

    @Column(name = "finish_reason", columnDefinition = "TEXT")
    private String finishReason;

    @Column(name = "model", columnDefinition = "TEXT")
    private String model;

    /** DBOS workflow id of this turn's LLM call — the link to {@code llm_usage_log.call_id}. */
    @Column(name = "call_id", columnDefinition = "TEXT")
    private String callId;
}
