package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Agent tool policy response")
public record AgentToolPolicyResponse(
        UUID id,
        UUID apiKeyPubId,
        String connectorCode,
        String connectorIdentity,
        String toolName,
        String effect,
        Integer priority,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentToolPolicyResponse from(AgentToolPolicy policy) {
        return new AgentToolPolicyResponse(
                policy.getId(),
                policy.getApiKeyPubId(),
                policy.getConnectorCode(),
                policy.getConnectorIdentity(),
                policy.getToolName(),
                policy.getEffect().name(),
                policy.getPriority(),
                policy.getDescription(),
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }
}
