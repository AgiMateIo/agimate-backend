package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Reference into {@code secrets} holding the API key (envelope, entity = {@code llm_provider}). */
    @Column(name = "secret_id")
    private UUID secretId;

    @Column(name = "api_key_mask", nullable = false, columnDefinition = "TEXT")
    private String apiKeyMask;

    /**
     * The models this provider is allowed to serve per purpose, in priority order
     * ({@code {"CHAT": ["m1","m2"], "VISION": []}}). An allowlist, not a hint: the resolver takes the
     * first live entry and never picks a model outside the list, so a purpose the user has not
     * configured is an error addressed to them rather than a guess. A missing key («not configured»)
     * and an empty list («switched off deliberately») are distinct and yield distinct messages.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "purpose_priority", columnDefinition = "JSONB")
    private Map<LlmPurpose, List<String>> purposePriority;

    /**
     * Provider-level extra parameters of the chat/completions body (OpenRouter {@code provider}
     * routing, {@code transforms}, …). Deep-merged with the per-model
     * {@link LlmProviderModel#getExtraBody()} (the model wins) in getLlmCredentials. NOT a secret —
     * it goes to the worker as a plain field; do not put API keys here. The models themselves live in
     * {@code llm_provider_models}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_body", columnDefinition = "JSONB")
    private Map<String, Object> extraBody;

    /**
     * The dialect used to ask this provider for a picture; {@code null} — the default of
     * {@link MediaTransportType#CHAT_MODALITIES}. Explicit and per-provider on purpose: it follows
     * neither from {@code providerType} (OpenRouter and Polza are both OPENAI_COMPATIBLE and speak
     * different dialects) nor from the model id (the same model is reached differently depending on
     * who serves it). See {@code docs/decisions/media-transport.md}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "media_transport", columnDefinition = "TEXT")
    private MediaTransportType mediaTransport;

    /** When the provider's /models was last polled successfully (an attribute of the listing, not of a model). */
    @Column(name = "models_refreshed_at")
    private LocalDateTime modelsRefreshedAt;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /**
     * The models declared for a purpose. {@code Optional.empty()} — the purpose is not configured at
     * all; an empty list — it is switched off deliberately. Every caller tells the two apart (the
     * messages differ), so they must not collapse into one «nothing here».
     */
    public Optional<List<String>> modelsFor(LlmPurpose purpose) {
        return Optional.ofNullable(purposePriority).map(p -> p.get(purpose));
    }
}
