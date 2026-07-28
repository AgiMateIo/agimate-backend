package ru.agimate.controlapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The result of a tool_call while polling: the client branches on {@link #status}, not on the body's
 * shape or the HTTP code. Keep polling while {@code status == PENDING}.
 */
@Schema(description = "Tool call result; poll while status is PENDING")
public record ToolResultResponse(
        @Schema(description = "Execution status")
        ToolResultStatus status,

        @Schema(description = "Tool output as raw JSON — set only for SUCCESS")
        String result,

        @Schema(description = "Error message — set only for ERROR")
        String error
) {
    public static ToolResultResponse pending() {
        return new ToolResultResponse(ToolResultStatus.PENDING, null, null);
    }

    public static ToolResultResponse success(String result) {
        return new ToolResultResponse(ToolResultStatus.SUCCESS, result, null);
    }

    public static ToolResultResponse error(String error) {
        return new ToolResultResponse(ToolResultStatus.ERROR, null, error);
    }
}
