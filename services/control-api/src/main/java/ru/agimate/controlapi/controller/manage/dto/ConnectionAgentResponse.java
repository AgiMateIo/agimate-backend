package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.ConnectionAgentView;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "An agent this connection is bound to (agent_connections row)")
public record ConnectionAgentResponse(
        @Schema(description = "Binding id — use it to manage policies for this binding")
        UUID id,

        @Schema(description = "Agent id")
        UUID agentId,

        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent description")
        String description,

        @Schema(description = "Whether the agent is enabled")
        boolean enabled,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the connection was bound to this agent")
        LocalDateTime createdAt
) {
    public static ConnectionAgentResponse from(ConnectionAgentView view) {
        Agent a = view.agent();
        return new ConnectionAgentResponse(
                view.binding().getId(),
                a.getId(),
                a.getName(),
                a.getDescription(),
                a.isEnabled(),
                view.binding().getCreatedAt());
    }
}
