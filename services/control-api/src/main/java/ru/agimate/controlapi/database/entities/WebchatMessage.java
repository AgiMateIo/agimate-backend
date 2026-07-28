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
 * UI log of the webchat channel: what was actually shown to the user (their messages and the agent's
 * delivered output), as opposed to {@link ChannelSessionMessage} — the session's LLM history.
 * {@code (session_id, message_id)} is unique: the worker sends deterministic {@code message_id}s, so
 * a DBOS replay creates no duplicates.
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

    /** The agent's output stream: {@code answer}/{@code progress}/{@code error}; null for direction=USER. */
    @Column(name = "stream", columnDefinition = "TEXT")
    private String stream;

    @Column(name = "message_id", nullable = false, columnDefinition = "TEXT")
    private String messageId;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    /** Attachments (reserved for files, null in Phase 1). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parts", columnDefinition = "JSONB")
    private List<Map<String, Object>> parts;
}
