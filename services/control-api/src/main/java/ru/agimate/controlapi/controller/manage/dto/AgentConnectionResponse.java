package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.IdentityScope;
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

        @Schema(description = "Identity scope of the connection (INSTANCE/AGENT/TEAM/USER/GLOBAL)")
        IdentityScope identityScope,

        @Schema(description = "Scope owner id (agentId/teamId/userId); null for INSTANCE/GLOBAL")
        UUID scopeId,

        @Schema(description = "Whether the connection is enabled")
        boolean enabled,

        LocalDateTime createdAt
) {
    public static AgentConnectionResponse from(AgentConnectionView view) {
        Connection c = view.connection();
        return new AgentConnectionResponse(
                view.binding().getId(),
                c.getId(),
                c.getConnectorCode(),
                c.getFullCode(),
                c.getName(),
                c.getIdentityScope(),
                c.getScopeId(),
                Boolean.TRUE.equals(c.getEnabled()),
                view.binding().getCreatedAt());
    }
}
