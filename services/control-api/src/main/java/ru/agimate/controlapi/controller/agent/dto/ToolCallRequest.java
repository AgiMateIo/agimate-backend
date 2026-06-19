package ru.agimate.controlapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.agimate.controlapi.service.dto.IToolCall;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to push tool use to device")
public class ToolCallRequest implements IToolCall {

    @NotNull(message = "Request id is required")
    private String id;

    @NotNull(message = "Connector code is required")
    private String connectorCode;

    @Schema(description = "Connector identity for ABAC")
    private String identity;

    @Schema(
            description = "Full name of tool",
            example = "tool.device.tts.speak",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "Tool name is required")
    private String name;

    @Schema(
            description = "Tool input"
    )
    private Map<String, Object> input;

    @Schema(description = "Agent session identifier")
    private String agentSessionId;

}
