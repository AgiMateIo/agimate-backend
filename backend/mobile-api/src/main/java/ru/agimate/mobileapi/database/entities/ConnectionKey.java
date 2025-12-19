package ru.agimate.mobileapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "connection_keys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "key_hash", nullable = false, columnDefinition = "TEXT")
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 8)
    private String keyPrefix;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "requests_per_hour")
    private Integer requestsPerHour;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    private Long usageCount = 0L;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "ip_whitelist", columnDefinition = "TEXT")
    private String ipWhitelist;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return enabled && !isDeleted() && !isExpired();
    }
}
