package ru.agimate.controlapi.service.dto;

import ru.agimate.controlapi.database.entities.ToolCallLog;

import java.util.Map;

public record ToolCallPayload(
        String id,
        String connectorCode,
        String connectionId,
        String name,
        Map<String, Object> input,
        String agentSessionId
) {
    public static ToolCallPayload from(ToolCallLog log) {
        return new ToolCallPayload(
                // The «control-api ↔ app» correlation goes by the log's PK (globally unique). external_id is
                // unique only within the pair (agent_id, external_id): with several agents on one app it collides,
                // and the app's echoed result could not be tied to its log unambiguously.
                log.getId().toString(),
                log.getConnectorCode(),
                log.getConnectionId(),
                log.getName(),
                log.getInput(),
                log.getAgentSessionId()
        );
    }
}
