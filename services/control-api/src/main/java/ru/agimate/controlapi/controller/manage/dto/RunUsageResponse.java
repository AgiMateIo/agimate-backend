package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.projections.RunUsageProjection;

@Schema(description = "What the run spent on the model: its LLM calls, summed")
public record RunUsageResponse(
        @Schema(description = "Prompt tokens")
        long inputTokens,

        @Schema(description = "Generated tokens, reasoning included")
        long outputTokens,

        @Schema(description = "Tokens served from the provider's prompt cache")
        long cacheReadTokens,

        @Schema(description = "Tokens written into the provider's prompt cache")
        long cacheWriteTokens,

        @Schema(description = "input + output. The cache counters are deliberately left out: providers "
                + "bill them as separate lines, and adding them would double-count the same prompt")
        long totalTokens,

        @Schema(description = "How many calls to the model the run made")
        long calls
) {
    /** A run that never reached the model: zeros rather than null — one shape for the client to render. */
    public static final RunUsageResponse NONE = new RunUsageResponse(0, 0, 0, 0, 0, 0);

    public static RunUsageResponse from(RunUsageProjection p) {
        return new RunUsageResponse(
                p.getInputTokens(),
                p.getOutputTokens(),
                p.getCacheReadTokens(),
                p.getCacheWriteTokens(),
                p.getInputTokens() + p.getOutputTokens(),
                p.getCalls());
    }
}
