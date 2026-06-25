package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.enums.PolicyKind;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Access refinement rule over a binding (default-allow model)")
public record AgentConnectionPolicyResponse(
        UUID id,

        @Schema(description = "Binding (agent_connections) id this rule refines")
        UUID agentConnectionId,

        @Schema(description = "TOOL or TRIGGER")
        PolicyKind kind,

        @Schema(description = "Tool/trigger name; null = binding-wide rule (whole connector)")
        String name,

        @Schema(description = "ALLOW or DENY")
        AccessEffect effect,

        @Schema(description = "Params filter: TOOL — restricts call arguments; TRIGGER — filters event params")
        Map<String, Object> paramsFilter,

        String description,

        String source,

        LocalDateTime createdAt
) {
    public static AgentConnectionPolicyResponse from(AgentConnectionPolicy p) {
        return new AgentConnectionPolicyResponse(
                p.getId(),
                p.getAgentConnectionId(),
                p.getKind(),
                p.getName(),
                p.getEffect(),
                p.getParamsFilter(),
                p.getDescription(),
                p.getSource(),
                p.getCreatedAt());
    }
}
