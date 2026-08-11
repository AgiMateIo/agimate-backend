package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "The run's input: the message list as it went into the first LLM call")
public record AgentRunPromptResponse(
        @Schema(description = "Run ID")
        UUID runId,

        @Schema(description = "System prompt, session history and the trigger's own turn, with the "
                + "ephemeral blocks (memory notes) that never reach the turn ledger. Null when the "
                + "snapshot was never taken — the run did not reach the loop, or it predates the feature")
        List<Map<String, Object>> messages
) {
}
