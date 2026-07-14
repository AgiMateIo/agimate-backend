package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.model.LlmModelInfo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "llm_providers", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_providers_user_name",
                columnNames = {"user_id", "name"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProvider extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, columnDefinition = "TEXT")
    private LlmProviderType providerType;

    @Column(name = "base_url", columnDefinition = "TEXT")
    private String baseUrl;

    /** Ссылка на {@code secrets} с API-ключом (envelope, entity = {@code llm_provider}). */
    @Column(name = "secret_id")
    private UUID secretId;

    @Column(name = "api_key_mask", nullable = false, columnDefinition = "TEXT")
    private String apiKeyMask;

    /** Модель по умолчанию: обязательна для платформенного провайдера (fallback без привязки). */
    @Column(name = "default_model", columnDefinition = "TEXT")
    private String defaultModel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "available_models", columnDefinition = "JSONB")
    private List<LlmModelInfo> availableModels;

    @Column(name = "models_refreshed_at")
    private LocalDateTime modelsRefreshedAt;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
