package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registry of an LLM provider's models (replacing the {@code available_models} JSONB cache):
 * discovery metadata, lifecycle (whether the model disappeared from the listing — see
 * {@link LlmProviderModelStatus}) and the per-model config override {@code extra_body}. A row is not
 * deleted when it disappears from the listing — it holds config, and {@code agent_llms} bindings may
 * reference the model.
 */
@Entity
@Table(name = "llm_provider_models", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_provider_models_llm_provider_id_model",
                columnNames = {"llm_provider_id", "model"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderModel extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "llm_provider_id", nullable = false)
    private UUID llmProviderId;

    /** The model's identifier at the provider (e.g. {@code moonshotai/kimi-k2.5}). */
    @Column(name = "model", nullable = false, columnDefinition = "TEXT")
    private String model;

    @Column(name = "display_name", columnDefinition = "TEXT")
    private String displayName;

    /** Context in tokens ({@code context_length} from /models, when the provider reports it). */
    @Column(name = "context_window")
    private Integer contextWindow;

    /** Ceiling on response tokens ({@code top_provider.max_completion_tokens}), when the provider reports it. */
    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    /** Input modalities ({@code ["text","image"]}) — «can this model see». */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_modalities", columnDefinition = "JSONB")
    private List<String> inputModalities;

    /** Output modalities ({@code ["image"]}, {@code ["audio"]}) — the basis of «model-as-a-tool» matching. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_modalities", columnDefinition = "JSONB")
    private List<String> outputModalities;

    /** Supported request parameters ({@code reasoning}, {@code tools}, …), when the provider reports them. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_parameters", columnDefinition = "JSONB")
    private List<String> supportedParameters;

    /** The provider's whole raw /models entry — the source for backfilling new fields without re-discovery. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_metadata", columnDefinition = "JSONB")
    private Map<String, Object> rawMetadata;

    /**
     * Per-model extra parameters of the chat/completions body (e.g. OpenRouter
     * {@code provider.only}/{@code require_parameters} to pin a vision hoster). Deep-merged on top of
     * {@link LlmProvider#getExtraBody()} in getLlmCredentials, and the model wins. NOT a secret — it
     * goes to the worker as a plain field.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_body", columnDefinition = "JSONB")
    private Map<String, Object> extraBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private LlmProviderModelStatus status = LlmProviderModelStatus.AVAILABLE;

    /** null = the model has never appeared in a listing (config entered by hand before a refresh). */
    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;
}
