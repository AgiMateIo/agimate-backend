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
 * Канонический full-fidelity ход рана: по одной записи на AgentChatMessage воркера (assistant/tool),
 * без капов — в отличие от капнутой канальной проекции {@link ChannelSessionMessage}. Пишется для
 * всех ранов, включая direct ({@code session_id} = null). Идемпотентность — UNIQUE (run_id, turn_index).
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

    /** Денорм ключ непрерывности (сейчас — сессия канала, null у direct-ранов); AgentSession отложен. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** Монотонный per-run счётчик хода: ключ идемпотентности UNIQUE (run_id, turn_index). */
    @Column(name = "turn_index", nullable = false)
    private Integer turnIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, columnDefinition = "TEXT")
    private AgentTurnRole role;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    /** Ассистент эмитил reasoning на этом ходе (маркер 💭). */
    @Column(name = "thinking", nullable = false)
    private boolean thinking;

    /** Вызовы тулов у assistant-хода ({@code [{id,name,argumentsJson}]}); null иначе. Без капа. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "JSONB")
    private List<Map<String, Object>> toolCalls;

    /** Результаты тулов у tool-хода ({@code [{id,name,outputJson,failed}]}); null иначе. Без капа. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_results", columnDefinition = "JSONB")
    private List<Map<String, Object>> toolResults;

    @Column(name = "finish_reason", columnDefinition = "TEXT")
    private String finishReason;

    @Column(name = "model", columnDefinition = "TEXT")
    private String model;

    /** DBOS workflow id LLM-вызова этого хода — связь с {@code llm_usage_log.call_id}. */
    @Column(name = "call_id", columnDefinition = "TEXT")
    private String callId;
}
