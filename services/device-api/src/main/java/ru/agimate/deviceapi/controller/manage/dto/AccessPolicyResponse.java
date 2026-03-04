package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.abac.AccessPolicy;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Access policy response")
public record AccessPolicyResponse(
        UUID id,
        String agentName,
        String connectorName,
        String connectorIdentity,
        String toolName,
        String effect,
        Integer priority,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AccessPolicyResponse from(AccessPolicy policy) {
        return new AccessPolicyResponse(
                policy.getId(),
                policy.getAgentName(),
                policy.getConnectorName(),
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
