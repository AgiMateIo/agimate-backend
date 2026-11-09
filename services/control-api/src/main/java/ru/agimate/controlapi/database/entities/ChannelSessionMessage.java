package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The platform's generic projection of a conversation as the channel delivered it — what the user
 * saw, as opposed to the model's transcript in {@link AgentRunTurn}. Written only for channels whose
 * handler has no message log of its own ({@code ChannelMessageLog}); webchat keeps its conversation
 * in {@code webchat_messages} instead.
 */
@Entity
@Table(name = "channel_session_messages", uniqueConstraints =
        @UniqueConstraint(name = "uq_channel_session_messages_run_id_seq", columnNames = {"run_id", "seq"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelSessionMessage extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** The run's monotonic counter (0 = inbound): the idempotency key UNIQUE (run_id, seq). Null on pre-reform rows. */
    @Column(name = "seq")
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "TEXT")
    private ChannelSessionMessageKind kind;

    /** Subtype of a PROGRESS message ({@code THINKING}/{@code TOOL_CALL}/{@code TEXT}); informational. */
    @Column(name = "progress_type", columnDefinition = "TEXT")
    private String progressType;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_input", columnDefinition = "JSONB")
    private Map<String, Object> triggerInput;

    /**
     * The ids of the tool calls a {@code TOOL_CALL} line names, in call order. Ids only — the same
     * contract as {@code webchat_messages.tool_call_ids} (docs/decisions/message-stores.md).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_call_ids", columnDefinition = "JSONB")
    private List<String> toolCallIds;
}
