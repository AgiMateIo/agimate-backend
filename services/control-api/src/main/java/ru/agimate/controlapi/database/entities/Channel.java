package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "channels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Channel extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    /** Имя {@code ChannelHandler}-а, обрабатывающего этот канал (см. ChannelHandlerRegistry). */
    @Column(name = "channel_handler", nullable = false, columnDefinition = "TEXT")
    private String channelHandler;

    /** Коннектор источника триггеров (и, как правило, ответов). */
    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    /** Identity источника: App.id или IntegrationCredentials.id строкой. */
    @Column(name = "identity", nullable = false, columnDefinition = "TEXT")
    private String identity;

    /** Произвольные настройки handler-а (reply-цель, шаблоны, messageField и т.п.). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> config;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return !isDeleted();
    }
}
