package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Уникальность {@code (connector_code, user_id, platform_identifier)} обеспечивается
 * partial unique index'ом в БД (см. миграцию {@code 2026/06/08-03-integration-credentials-partial-unique.xml}):
 * индекс действует только для активных строк {@code WHERE deleted_at IS NULL}, поэтому
 * soft-deleted записи не блокируют повторное создание интеграции с тем же платформенным
 * идентификатором. {@code @UniqueConstraint} в JPA не умеет в partial — не декларируем.
 */
@Entity
@Table(name = "integration_credentials")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationCredentials extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name", columnDefinition = "TEXT")
    private String name;

    @Column(name = "platform_identifier", nullable = false, columnDefinition = "TEXT")
    private String platformIdentifier;

    @Column(name = "encrypted_data", nullable = false, columnDefinition = "TEXT")
    private String encryptedData;

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
