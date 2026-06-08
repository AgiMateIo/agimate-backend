package ru.agimate.controlapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Tool definition with name, description, and parameters")
public record ToolDefinition(
        @Schema(description = "Tool name")
        String name,

        @Schema(description = "Tool description")
        String description,

        @Schema(description = "Tool parameter names")
        List<String> params
) {
}
