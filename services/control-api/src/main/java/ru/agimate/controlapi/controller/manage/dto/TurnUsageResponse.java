package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.LlmUsageLog;

@Schema(description = "What one turn spent on the model")
public record TurnUsageResponse(
        @Schema(description = "Prompt tokens")
        long inputTokens,

        @Schema(description = "Generated tokens, reasoning included")
        long outputTokens,

        @Schema(description = "Tokens served from the provider's prompt cache")
        long cacheReadTokens,

        @Schema(description = "Tokens written into the provider's prompt cache")
        long cacheWriteTokens,

        @Schema(description = "input + output; the cache counters stay out, as in the run's total")
        long totalTokens
) {
    /**
     * No {@code calls} counter here, unlike the run's total: a turn is one call by construction, and a
     * field that is always 1 only invites the reader to wonder when it is not.
     */
    public static TurnUsageResponse from(LlmUsageLog log) {
        long input = log.getInputTokens() == null ? 0 : log.getInputTokens();
        long output = log.getOutputTokens() == null ? 0 : log.getOutputTokens();
        return new TurnUsageResponse(
                input,
                output,
                log.getCacheReadTokens() == null ? 0 : log.getCacheReadTokens(),
                log.getCacheWriteTokens() == null ? 0 : log.getCacheWriteTokens(),
                input + output);
    }
}
