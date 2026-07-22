package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.SQLRestriction;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.AgentType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Агенты удаляются мягко: {@code deleted_at} проставляется вместо физического DELETE, строка
 * остаётся ради целостности всех ссылающихся таблиц (channels, agent_runs, board_*,
 * channel_session_messages, agent_llms/skills, secrets). {@link SQLRestriction} скрывает
 * удалённых из всех выборок и join'ов (включая auth {@code findByKeyId} и роутинг триггеров) —
 * ни одного ручного {@code deletedAt IS NULL} по месту не требуется.
 */
@Entity
@Table(name = "agents", uniqueConstraints = @UniqueConstraint(columnNames = "key_id"))
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "key_hash", nullable = false, columnDefinition = "TEXT")
    private String keyHash;

    @Column(name = "key_id", nullable = false, unique = true, columnDefinition = "TEXT")
    private String keyId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private AgentType type = AgentType.CENTRIFUGO;

    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl;

    /**
     * Значение Authorization-заголовка для outbound-webhook'ов: ссылка на {@code secrets}
     * (entity = {@code agent_webhook_auth}, AAD-owner = {@code id}). {@code null} — без auth.
     */
    @Column(name = "webhook_auth_secret_id")
    private UUID webhookAuthSecretId;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "agentic_team_id")
    private UUID agenticTeamId;

    /** Код пресета, с которого стартовал мастер создания (аналитика воронки); без FK. */
    @Column(name = "preset_code", columnDefinition = "TEXT")
    private String presetCode;

    /** Момент мягкого удаления; {@code null} — активен. Скрытие из выборок — через {@link SQLRestriction}. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean hasWebhookAuth() {
        return webhookAuthSecretId != null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
