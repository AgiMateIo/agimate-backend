package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Правка лимита существующей квоты. {@code subjectKind}/{@code window} не меняются — это
 * бизнес-ключ квоты ({@code uq_llm_quotas_key}); смена субъекта/окна = другая квота (delete+create).
 */
@Schema(description = "Update a quota's token limit")
public record UpdateLlmQuotaRequest(
        @NotNull
        @Min(1)
        @Schema(description = "New token limit per window")
        Long limitTokens
) {
}
