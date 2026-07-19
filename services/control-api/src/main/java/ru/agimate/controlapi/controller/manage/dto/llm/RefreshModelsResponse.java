package ru.agimate.controlapi.controller.manage.dto.llm;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Result of LLM provider models refresh: the full registry after the sync "
        + "(disappeared models stay with status UNAVAILABLE)")
public record RefreshModelsResponse(
        @Schema(description = "Model registry rows after the refresh")
        List<LlmProviderModelResponse> models,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last successful listing refresh timestamp")
        LocalDateTime refreshedAt
) {
}
