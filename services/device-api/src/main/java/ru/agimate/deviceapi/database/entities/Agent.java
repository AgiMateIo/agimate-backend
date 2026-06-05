package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.deviceapi.database.enums.AgentType;

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

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private AgentType type = AgentType.CENTRIFUGO;

    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl;

    @Column(name = "webhook_auth_header", columnDefinition = "TEXT")
    private String webhookAuthHeader;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "agentic_team_id")
    private UUID agenticTeamId;

    public boolean hasWebhookAuth() {
        return webhookAuthHeader != null && !webhookAuthHeader.isBlank();
    }
}
