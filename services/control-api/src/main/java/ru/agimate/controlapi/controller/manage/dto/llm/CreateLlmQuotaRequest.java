package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;

@Schema(description = "Create a token quota on an LLM provider")
public record CreateLlmQuotaRequest(
        @NotNull
        @Schema(description = "Quota subject: TOTAL — whole provider, AGENT — each agent, USER — each user")
        UsageSubjectKind subjectKind,

        @NotNull
        @Schema(description = "Calendar window (UTC)")
        UsageWindow window,

        @NotNull
        @Min(1)
        @Schema(description = "Token limit per window")
        Long limitTokens
) {
}
