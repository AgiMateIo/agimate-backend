package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "llm_providers", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_providers_user_name",
                columnNames = {"user_pub_id", "name"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProvider extends BaseEntity {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, columnDefinition = "TEXT")
    private LlmProviderType providerType;

    @Column(name = "base_url", columnDefinition = "TEXT")
    private String baseUrl;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "api_key_mask", nullable = false, columnDefinition = "TEXT")
    private String apiKeyMask;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "available_models", columnDefinition = "JSONB")
    private List<String> availableModels;

    @Column(name = "models_refreshed_at")
    private LocalDateTime modelsRefreshedAt;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
