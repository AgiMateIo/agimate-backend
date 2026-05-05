package ru.agimate.deviceapi.service.dto;

import ru.agimate.deviceapi.database.entities.ToolUseLog;

import java.util.Map;

public record ToolUsePayload(
        String id,
        String connectorCode,
        String identity,
        String name,
        Map<String, Object> input,
        String agentSessionId
) {
    public static ToolUsePayload from(ToolUseLog log) {
        return new ToolUsePayload(
                log.getToolUseId(),
                log.getConnectorCode(),
                log.getIdentity(),
                log.getToolName(),
                log.getInput(),
                log.getAgentSessionId()
        );
    }
}
