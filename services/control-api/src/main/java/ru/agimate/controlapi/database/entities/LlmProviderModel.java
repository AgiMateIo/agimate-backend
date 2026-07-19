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
 * Реестр моделей LLM-провайдера (замена JSONB-кэша {@code available_models}): discovery-метаданные,
 * lifecycle (пропала ли модель из листинга — см. {@link LlmProviderModelStatus}) и пер-модельный
 * конфиг-оверрайд {@code extra_body}. Строка при пропаже из листинга не удаляется — на ней конфиг,
 * и на модель могут ссылаться биндинги {@code agent_llms}.
 */
@Entity
@Table(name = "llm_provider_models", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_provider_models_provider_model",
                columnNames = {"provider_id", "model"})
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

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /** Идентификатор модели у провайдера (например {@code moonshotai/kimi-k2.5}). */
    @Column(name = "model", nullable = false, columnDefinition = "TEXT")
    private String model;

    @Column(name = "display_name", columnDefinition = "TEXT")
    private String displayName;

    /** Контекст в токенах ({@code context_length} из /models, если провайдер отдаёт). */
    @Column(name = "context_window")
    private Integer contextWindow;

    /** Потолок токенов ответа ({@code top_provider.max_completion_tokens}), если провайдер отдаёт. */
    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    /** Входные модальности ({@code ["text","image"]}) — «умеет ли модель зрение». */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_modalities", columnDefinition = "JSONB")
    private List<String> inputModalities;

    /** Выходные модальности ({@code ["image"]}, {@code ["audio"]}) — основа матчинга «модель-как-инструмент». */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_modalities", columnDefinition = "JSONB")
    private List<String> outputModalities;

    /** Поддерживаемые параметры запроса ({@code reasoning}, {@code tools}, …), если провайдер отдаёт. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_parameters", columnDefinition = "JSONB")
    private List<String> supportedParameters;

    /** Сырой entry ответа /models провайдера целиком — источник для backfill новых полей без ре-дискавери. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_metadata", columnDefinition = "JSONB")
    private Map<String, Object> rawMetadata;

    /**
     * Пер-модельные доп. параметры тела chat/completions (например OpenRouter
     * {@code provider.only}/{@code require_parameters} для пиннинга vision-хостера). Deep-merge
     * поверх {@link LlmProvider#getExtraBody()} в getLlmCredentials, модель побеждает.
     * НЕ секрет — уходит воркеру открытым полем.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_body", columnDefinition = "JSONB")
    private Map<String, Object> extraBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private LlmProviderModelStatus status = LlmProviderModelStatus.AVAILABLE;

    /** null = модель ни разу не встречалась в листинге (конфиг заведён руками до refresh). */
    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;
}
