package ru.agimate.deviceapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.agimate.deviceapi.service.IToolUse;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to push tool use to device")
public class ToolUseRequest implements IToolUse {

    @NotNull(message = "Request id is required")
    private String id;

    @Schema(description = "Agent session identifier")
    private String agentSessionId;

    @Schema(
            description = "Full name of tool",
            example = "tool.device.tts.speak",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "Tool name is required")
    private String name;

    @Schema(
            description = "Tool parameters"
    )
    private Map<String, Object> params;

    @Schema(description = "Connector identity for ABAC")
    private String identity;
}
