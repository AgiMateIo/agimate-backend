package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.AgentConnectionView;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "A connector instance bound to an agent (agent_connections row)")
public record AgentConnectionResponse(
        @Schema(description = "Binding id — use it to manage policies for this binding")
        UUID id,

        @Schema(description = "Connection (connector instance) id")
        UUID connectionId,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Stable client handle of the instance")
        String fullCode,

        @Schema(description = "Human-readable name")
        String name,

        @Schema(description = "Whether the connection is enabled")
        boolean enabled,

        @Schema(description = "Binding of an internal connector: not removable here, its source of truth is the agent's skills")
        boolean managedBySkills,

        @Schema(description = "How many of the agent's skills work with this instance — what breaks if it is unbound")
        long usedBySkills,

        LocalDateTime createdAt
) {
    public static AgentConnectionResponse from(AgentConnectionView view, long usedBySkills) {
        Connection c = view.connection();
        return new AgentConnectionResponse(
                view.binding().getId(),
                c.getId(),
                c.getConnectorCode(),
                c.getFullCode(),
                c.getName(),
                Boolean.TRUE.equals(c.getEnabled()),
                view.managedBySkills(),
                usedBySkills,
                view.binding().getCreatedAt());
    }
}
