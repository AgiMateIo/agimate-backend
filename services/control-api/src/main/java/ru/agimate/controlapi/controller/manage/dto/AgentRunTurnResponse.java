package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "One turn of a run, as the model saw it")
public record AgentRunTurnResponse(
        @Schema(description = "Monotonic per-run turn counter; 0 is the inbound turn")
        int turnIndex,

        @Schema(description = "USER (the incoming turn), ASSISTANT or TOOL. SYSTEM is never recorded — "
                + "the system prompt is kept once per run, in the run's prompt snapshot")
        AgentTurnRole role,

        @Schema(description = "The turn's text: the request on USER, the answer or the preamble before "
                + "tool calls on ASSISTANT, empty on TOOL")
        String text,

        @Schema(description = "The model's reasoning, uncapped; null when it did not reason")
        String thinkingText,

        @Schema(description = "Tool calls of an ASSISTANT turn: [{id, name, argumentsJson}]")
        List<Map<String, Object>> toolCalls,

        @Schema(description = "Tool results of a TOOL turn: [{id, name, outputJson, failed}]")
        List<Map<String, Object>> toolResults,

        @Schema(description = "Why the model stopped on this turn (assistant turns only)")
        String finishReason,

        @Schema(description = "Model that produced the turn (assistant turns only)")
        String model,

        @Schema(description = "Id of this turn's LLM call — the join key to the usage log")
        String callId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the turn was recorded")
        LocalDateTime createdAt
) {
    public static AgentRunTurnResponse from(AgentRunTurn turn) {
        return new AgentRunTurnResponse(
                turn.getTurnIndex(),
                turn.getRole(),
                turn.getText(),
                turn.getThinkingText(),
                turn.getToolCalls(),
                turn.getToolResults(),
                turn.getFinishReason(),
                turn.getModel(),
                turn.getCallId(),
                turn.getCreatedAt()
        );
    }
}
