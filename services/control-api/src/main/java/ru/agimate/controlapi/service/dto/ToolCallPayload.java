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
                // Корреляция «control-api ↔ устройство» — по PK лога (глобально уникален). external_id
                // уникален лишь в паре (agent_id, external_id): при нескольких агентах на одном app он
                // коллизирует, и эхо-результат устройства нельзя было бы однозначно привязать к логу.
                log.getId().toString(),
                log.getConnectorCode(),
                log.getConnectionId(),
                log.getName(),
                log.getInput(),
                log.getAgentSessionId()
        );
    }
}
