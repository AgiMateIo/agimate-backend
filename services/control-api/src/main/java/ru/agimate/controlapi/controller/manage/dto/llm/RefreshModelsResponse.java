package ru.agimate.controlapi.controller.manage.dto.llm;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Result of LLM provider models refresh")
public record RefreshModelsResponse(
        @Schema(description = "Refreshed list of available model names")
        List<String> availableModels,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Refresh timestamp")
        LocalDateTime refreshedAt
) {
}
