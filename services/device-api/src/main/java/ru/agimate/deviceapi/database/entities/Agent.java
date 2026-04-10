package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "key_hash", nullable = false, columnDefinition = "TEXT")
    private String keyHash;

    @Column(name = "key_id", nullable = false, unique = true, columnDefinition = "TEXT")
    private String keyId;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_destination", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private TriggerDestination triggerDestination = TriggerDestination.CENTRIFUGO;

    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl;

    @Column(name = "webhook_auth_header", columnDefinition = "TEXT")
    private String webhookAuthHeader;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "agentic_team_id")
    private Long agenticTeamId;

    public boolean hasWebhookAuth() {
        return webhookAuthHeader != null && !webhookAuthHeader.isBlank();
    }
}
