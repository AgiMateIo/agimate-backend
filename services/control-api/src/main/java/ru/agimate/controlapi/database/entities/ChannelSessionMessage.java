package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "channel_session_messages")
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

    /** Subtype of a PROGRESS message (THINKING/TOOL_CALL/TEXT) — used by the historyDetail filter. */
    @Column(name = "progress_type", columnDefinition = "TEXT")
    private String progressType;

    /**
     * Every message of a run is written with false; the final ANSWER marks the whole run_id true.
     * History of later runs sees only completed ones — unfinished and failed runs drop out.
     */
    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /**
     * v2.1: the structural record of a tool turn ({@code ToolTurnRecord}: text + calls + results) on
     * PROGRESS/TOOL_CALL — history of later runs hands it to the worker as native
     * tool_use/tool_result. Null on every other row. Pre-reform rows may have stored an
     * old-format serialised LLM turn here — those are distinguishable by kind (REQUEST/RESPONSE).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_json", columnDefinition = "JSONB")
    private Map<String, Object> messageJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_input", columnDefinition = "JSONB")
    private Map<String, Object> triggerInput;
}
