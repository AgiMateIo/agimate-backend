package ru.agimate.controlapi.service.dto.llm;

import ru.agimate.controlapi.database.enums.UsageWindow;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * LLM token usage for one provider (current calendar windows, UTC) — the service-layer shape of the
 * controller's {@code LlmUsageResponse}, returned by {@code LlmUsageQueryService.usageForUserSnapshot}
 * so the connector layer does not depend on {@code controller/**}. The perspective depends on the
 * provider: BYOK — TOTAL (the whole provider), platform — USER (the calling user).
 *
 * @param llmProviderId provider id; null for the platform provider (not addressable by the user)
 * @param providerName  provider name
 * @param source        "USER" — own (BYOK) provider, usage of the whole provider;
 *                      "PLATFORM" — platform provider, usage of the current user
 * @param windows       current DAY and MONTH windows
 */
public record LlmUsageSnapshot(
        UUID llmProviderId,
        String providerName,
        String source,
        List<WindowUsage> windows
) {

    /** Usage within one calendar window. */
    public record WindowUsage(
            UsageWindow window,
            LocalDate windowStart,
            long usedTokens,
            int requests,
            Long limitTokens,
            Long remainingTokens
    ) {
    }
}
