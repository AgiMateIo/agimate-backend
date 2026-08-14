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
 * Agents are deleted softly: {@code deleted_at} is set instead of a physical DELETE, and the row
 * stays for the integrity of every referencing table (channels, agent_runs, board_*,
 * channel_session_messages, agent_llms/skills, secrets). {@link SQLRestriction} hides deleted rows
 * from every query and join (including the auth {@code findByKeyId} and trigger routing) — not a
 * single manual {@code deletedAt IS NULL} is needed anywhere.
 */
@Entity
@Table(name = "agents", uniqueConstraints =
        @UniqueConstraint(name = "uq_agents_key_id", columnNames = "key_id"))
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

    @Column(name = "key_id", nullable = false, columnDefinition = "TEXT")
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
     * The Authorization header value for outbound webhooks: a reference into {@code secrets}
     * (entity = {@code agent_webhook_auth}, AAD owner = {@code id}). {@code null} — no auth.
     */
    @Column(name = "webhook_auth_secret_id")
    private UUID webhookAuthSecretId;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "agentic_team_id")
    private UUID agenticTeamId;

    /** Name (machine code) of the preset the creation wizard started from (funnel analytics); no FK. */
    @Column(name = "preset_name", columnDefinition = "TEXT")
    private String presetName;

    /** Moment of the soft delete; {@code null} — active. Hiding from queries is done by {@link SQLRestriction}. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean hasWebhookAuth() {
        return webhookAuthSecretId != null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
