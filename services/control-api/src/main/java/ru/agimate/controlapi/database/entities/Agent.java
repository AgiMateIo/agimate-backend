package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.AgentType;

import java.util.UUID;

@Entity
@Table(name = "agents", uniqueConstraints = @UniqueConstraint(columnNames = "key_id"))
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

    public boolean hasWebhookAuth() {
        return webhookAuthSecretId != null;
    }
}
