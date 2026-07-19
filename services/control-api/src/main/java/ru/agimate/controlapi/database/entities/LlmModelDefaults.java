package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;

import java.util.List;
import java.util.UUID;

/**
 * Курируемый фолбэк капабилити для моделей, чьи параметры не удаётся дискаверить (провайдеры вроде
 * OpenAI/Anthropic отдают в {@code /models} голые id). Глобальная по id first-party модели — он
 * однозначен, роутинга между хостерами нет (агрегаторы отдают метаданные и в фолбэк не попадают).
 *
 * <p>Fallback-only, пер-полевой: при {@code refreshModels} null-поля discovery добираются отсюда
 * (discovered побеждает). Отсутствие строки = капабилити {@code unknown}, как без справочника —
 * никакой обязательной синхронизации с {@link LlmProviderModel}.
 */
@Entity
@Table(name = "llm_model_defaults", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_model_defaults_model", columnNames = {"model"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmModelDefaults extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** id модели у провайдера (например {@code whisper-1}, {@code gpt-image-1}). */
    @Column(name = "model", nullable = false, columnDefinition = "TEXT")
    private String model;

    @Column(name = "display_name", columnDefinition = "TEXT")
    private String displayName;

    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_modalities", columnDefinition = "JSONB")
    private List<String> inputModalities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_modalities", columnDefinition = "JSONB")
    private List<String> outputModalities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_parameters", columnDefinition = "JSONB")
    private List<String> supportedParameters;
}
