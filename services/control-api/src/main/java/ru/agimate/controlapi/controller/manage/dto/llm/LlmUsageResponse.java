package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.UsageWindow;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "LLM token usage for one provider (current calendar windows, UTC)")
public record LlmUsageResponse(
        @Schema(description = "Provider ID; null for the platform provider (not addressable)")
        UUID llmProviderId,

        @Schema(description = "Provider name")
        String providerName,

        @Schema(description = "USER — own (BYOK) provider, usage of the whole provider; "
                + "PLATFORM — platform provider, usage of the current user")
        AgentLlmResponse.Source source,

        @Schema(description = "Current DAY and MONTH windows")
        List<WindowUsage> windows
) {
    @Schema(description = "Usage within one calendar window")
    public record WindowUsage(
            @Schema(description = "Window kind")
            UsageWindow window,

            @Schema(description = "Window start (UTC)")
            LocalDate windowStart,

            @Schema(description = "Tokens used (input + output + cache_write)")
            long usedTokens,

            @Schema(description = "LLM calls in the window")
            int requests,

            @Schema(description = "Quota limit; null when no quota is set")
            Long limitTokens,

            @Schema(description = "max(0, limit - used); null when no quota is set")
            Long remainingTokens
    ) {
    }
}
