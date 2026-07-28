package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Editing an existing quota's limit. {@code subjectKind}/{@code window} do not change — they are the
 * quota's business key ({@code uq_llm_quotas_key}); changing the subject or the window means a
 * different quota (delete+create).
 */
@Schema(description = "Update a quota's token limit")
public record UpdateLlmQuotaRequest(
        @NotNull
        @Min(1)
        @Schema(description = "New token limit per window")
        Long limitTokens
) {
}
