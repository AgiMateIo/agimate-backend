package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "integrations", uniqueConstraints = {
        @UniqueConstraint(name = "uq_integrations_user_platform",
                columnNames = {"user_pub_id", "platform_type", "platform_identifier"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Integration extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_id", nullable = false)
    private App app;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "platform_id", nullable = false)
    private Platform platform;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "name", columnDefinition = "TEXT")
    private String name;

    @Column(name = "platform_type", nullable = false, columnDefinition = "TEXT")
    private String platformType;

    @Column(name = "platform_identifier", nullable = false, columnDefinition = "TEXT")
    private String platformIdentifier;

    @Column(name = "encrypted_data", nullable = false, columnDefinition = "TEXT")
    private String encryptedData;

    @Column(name = "encryption_iv", nullable = false, columnDefinition = "TEXT")
    private String encryptionIv;

    @Column(name = "webhook_secret", columnDefinition = "TEXT")
    private String webhookSecret;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return enabled && !isDeleted();
    }
}
