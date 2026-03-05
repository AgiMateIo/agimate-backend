package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Agent trigger policy response")
public record AgentTriggerPolicyResponse(
        UUID id,
        UUID agentPubId,
        UUID userPubId,
        String connectorCode,
        String connectorIdentity,
        String triggerName,
        String effect,
        Integer priority,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentTriggerPolicyResponse from(AgentTriggerPolicy policy) {
        return new AgentTriggerPolicyResponse(
                policy.getId(),
                policy.getAgentPubId(),
                policy.getUserPubId(),
                policy.getConnectorCode(),
                policy.getConnectorIdentity(),
                policy.getTriggerName(),
                policy.getEffect().name(),
                policy.getPriority(),
                policy.getDescription(),
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }
}
