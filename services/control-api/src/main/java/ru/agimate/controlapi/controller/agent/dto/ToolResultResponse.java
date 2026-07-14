package ru.agimate.controlapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Результат tool_call при опросе: клиент ветвится по {@link #status}, а не по форме тела/HTTP-коду.
 * Опрашивать, пока {@code status == PENDING}.
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
