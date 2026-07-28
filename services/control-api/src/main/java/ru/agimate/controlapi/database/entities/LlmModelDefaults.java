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
 * A curated capability fallback for models whose parameters cannot be discovered (providers such as
 * OpenAI and Anthropic return bare ids from {@code /models}). Global by first-party model id — that
 * id is unambiguous and there is no routing between hosters (aggregators do return metadata and
 * never reach this fallback).
 *
 * <p>Fallback-only and per-field: during {@code refreshModels} the null fields of discovery are
 * filled in from here (discovered wins). A missing row means capabilities are {@code unknown}, just
 * as without the reference table — no mandatory synchronisation with {@link LlmProviderModel}.
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

    /** The model's id at the provider (e.g. {@code whisper-1}, {@code gpt-image-1}). */
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
