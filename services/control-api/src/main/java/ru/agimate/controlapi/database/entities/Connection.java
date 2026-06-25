package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Единый реестр экземпляров коннекторов. {@code id} = {@code identity} во всём downstream
 * (channels, policies, trigger_logs, tool_call_logs, connector_jobs). Сворачивает
 * {@code integration_credentials}; на {@code apps} ссылается через {@link #appId} (данные
 * устройства/auth не дублируются).
 *
 * <p>{@code full_code = connector_code + "_" + sub_code} — стабильный клиентский handle и префикс
 * неймспейса тулов (mcp_context7.resolve-library-id); для статических singleton = {@code connector_code}.
 *
 * <p>Уникальность среди активных строк: {@code (connector_code, user_id, sub_code)} и
 * {@code (full_code, user_id)} — partial unique индексы {@code WHERE deleted_at IS NULL}
 * (см. миграцию {@code 2026/06/25-03-connections.xml}). JPA {@code @UniqueConstraint} не выражает
 * partial — не декларируем.
 */
@Entity
@Table(name = "connections")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Connection extends BaseEntity {

    /**
     * Назначается явно при создании ({@code UUIDUtils.generateUUIDv8()} для интеграций,
     * {@code app.id} для APP, старый id при бэкфилле) — id = {@code identity} во всём downstream,
     * поэтому не генерится БД.
     */
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "sub_code", columnDefinition = "TEXT")
    private String subCode;

    @Column(name = "full_code", nullable = false, columnDefinition = "TEXT")
    private String fullCode;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name", columnDefinition = "TEXT")
    private String name;

    /** Outbound-credentials (telegram/mcp): ссылка на {@code secrets}. {@code null} для internal/app. */
    @Column(name = "secret_id")
    private UUID secretId;

    /** APP-тип: ссылка на {@code apps} (device-auth/linking не дублируем). */
    @Column(name = "app_id")
    private UUID appId;

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
