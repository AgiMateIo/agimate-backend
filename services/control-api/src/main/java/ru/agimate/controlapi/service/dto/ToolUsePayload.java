package ru.agimate.controlapi.service.dto;

import ru.agimate.controlapi.database.entities.ToolCallLog;

import java.util.Map;

public record ToolUsePayload(
        String id,
        String connectorCode,
        String identity,
        String name,
        Map<String, Object> input,
        String agentSessionId
) {
    public static ToolUsePayload from(ToolCallLog log) {
        return new ToolUsePayload(
                log.getExternalId(),
                log.getConnectorCode(),
                log.getIdentity(),
                log.getName(),
                log.getInput(),
                log.getAgentSessionId()
        );
    }
}
