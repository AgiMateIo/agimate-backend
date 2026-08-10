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
@Schema(description = "Request to push a tool call to a connected app")
public class ToolCallRequest implements IToolCall {

    @NotNull(message = "Request id is required")
    private String id;

    @Schema(description = "Connector code; optional — derived from connectionId (connections.connector_code) when omitted")
    private String connectorCode;

    @Schema(description = "Connection instance handle (connections.id as string) for ABAC routing")
    private String connectionId;

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

    @Schema(description = "Initiating agent run id (agent_runs.id); null for app-originated calls")
    private String runId;

}
