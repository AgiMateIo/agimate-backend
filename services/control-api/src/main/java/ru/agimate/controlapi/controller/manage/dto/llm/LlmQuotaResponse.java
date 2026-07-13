package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.LlmQuota;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;

import java.util.UUID;

@Schema(description = "LLM token quota on a provider")
public record LlmQuotaResponse(
        @Schema(description = "Quota ID")
        UUID id,

        @Schema(description = "Quota subject: TOTAL — whole provider, AGENT — each agent, USER — each user")
        UsageSubjectKind subjectKind,

        @Schema(description = "Calendar window (UTC)")
        UsageWindow window,

        @Schema(description = "Token limit per window (input + output + cache_write)")
        Long limitTokens
) {
    public static LlmQuotaResponse from(LlmQuota quota) {
        return new LlmQuotaResponse(quota.getId(), quota.getSubjectKind(),
                quota.getWindow(), quota.getLimitTokens());
    }
}
