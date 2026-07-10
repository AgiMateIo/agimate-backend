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

    /** Монотонный счётчик рана (0 = inbound): ключ идемпотентности UNIQUE (run_id, seq). Null у дореформенных строк. */
    @Column(name = "seq")
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "TEXT")
    private ChannelSessionMessageKind kind;

    /** Подтип PROGRESS-сообщения (THINKING/TOOL_CALL/TEXT) — для фильтра historyDetail. */
    @Column(name = "progress_type", columnDefinition = "TEXT")
    private String progressType;

    /**
     * Все сообщения рана пишутся с false; финальный ANSWER помечает весь run_id true.
     * История следующих ранов видит только completed — незавершённые/упавшие раны выпадают.
     */
    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /** Дореформенный сериализованный LLM-ход; v2 больше не пишет (nullable), остаётся для чтения. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_json", columnDefinition = "JSONB")
    private Map<String, Object> messageJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_input", columnDefinition = "JSONB")
    private Map<String, Object> triggerInput;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "cache_read_tokens")
    private Integer cacheReadTokens;

    @Column(name = "cache_write_tokens")
    private Integer cacheWriteTokens;

    @Column(name = "model_name", columnDefinition = "TEXT")
    private String modelName;

    @Column(name = "provider_name", columnDefinition = "TEXT")
    private String providerName;
}
