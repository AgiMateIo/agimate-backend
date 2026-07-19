package ru.agimate.controlapi.controller.manage.dto.llm;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Model registry entry of an LLM provider: discovery metadata, availability "
        + "status and per-model extra_body override")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmProviderModelResponse(
        @Schema(description = "Row ID")
        UUID id,

        @Schema(description = "Provider-specific model identifier (e.g. \"moonshotai/kimi-k2.5\")")
        String model,

        @Schema(description = "Human-readable name (when the provider supplies one)")
        String displayName,

        @Schema(description = "Context window in tokens (when known)")
        Integer contextWindow,

        @Schema(description = "Max output tokens (when known)")
        Integer maxOutputTokens,

        @Schema(description = "Input modalities, e.g. [\"text\",\"image\"] — \"image\" means the model has vision")
        List<String> inputModalities,

        @Schema(description = "Output modalities, e.g. [\"image\"] / [\"audio\"] — basis for model-as-tool routing")
        List<String> outputModalities,

        @Schema(description = "Supported request parameters, e.g. [\"tools\",\"reasoning\"]")
        List<String> supportedParameters,

        @Schema(description = "Per-model extra chat/completions body fields (deep-merged over the "
                + "provider-level extra_body; model wins)")
        Map<String, Object> extraBody,

        @Schema(description = "Availability per the last successful listing refresh (advisory: "
                + "UNAVAILABLE does not block LLM calls)")
        LlmProviderModelStatus status,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "First time the model appeared in the provider's listing "
                + "(null — never listed, config added manually)")
        LocalDateTime firstSeenAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last time the model appeared in the provider's listing")
        LocalDateTime lastSeenAt
) {
    public static LlmProviderModelResponse from(LlmProviderModel entity) {
        return new LlmProviderModelResponse(
                entity.getId(),
                entity.getModel(),
                entity.getDisplayName(),
                entity.getContextWindow(),
                entity.getMaxOutputTokens(),
                entity.getInputModalities(),
                entity.getOutputModalities(),
                entity.getSupportedParameters(),
                entity.getExtraBody(),
                entity.getStatus(),
                entity.getFirstSeenAt(),
                entity.getLastSeenAt()
        );
    }
}
