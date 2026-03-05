package ru.agimate.deviceapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to save tool use result from agent")
public class AgentToolResultRequest {

    @NotNull(message = "Tool use ID is required")
    @Schema(description = "Tool use correlation ID")
    private String toolUseId;

    @Schema(description = "Tool execution output")
    private String output;

    @Schema(description = "Tool execution error")
    private String error;
}
