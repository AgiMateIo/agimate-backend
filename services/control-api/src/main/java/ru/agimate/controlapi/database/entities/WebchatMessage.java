package ru.agimate.controlapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UI-лог webchat-канала: что реально показано пользователю (его сообщения и доставленный вывод
 * агента), в отличие от {@link ChannelSessionMessage} — LLM-истории сессии.
 * {@code (session_id, message_id)} уникален: worker шлёт детерминированные {@code message_id},
 * поэтому DBOS-replay не создаёт дублей.
 */
@Entity
@Table(name = "webchat_messages", uniqueConstraints = {
        @UniqueConstraint(name = "uq_webchat_messages_session_message",
                columnNames = {"session_id", "message_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebchatMessage extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, columnDefinition = "TEXT")
    private WebchatMessageDirection direction;

    /** Поток вывода агента: {@code answer}/{@code progress}/{@code error}; null для direction=USER. */
    @Column(name = "stream", columnDefinition = "TEXT")
    private String stream;

    @Column(name = "message_id", nullable = false, columnDefinition = "TEXT")
    private String messageId;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    /** Вложения (резерв под файлы, Фаза 1 — null). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parts", columnDefinition = "JSONB")
    private List<Map<String, Object>> parts;
}
