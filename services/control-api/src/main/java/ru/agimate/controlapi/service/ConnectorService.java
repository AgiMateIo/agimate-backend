package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.integrations.IntegrationToolExecutorService;
import ru.agimate.controlapi.connectors.internal.ServerToolExecutorService;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.ToolUseLog;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.dto.ToolUsePayload;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorService {

    private final ConnectorRepository connectorRepository;

    private final AppRepository appRepository;

    private final CentrifugoService centrifugoService;

    private final IntegrationCredentialsRepository integrationCredentialsRepository;

    private final IntegrationToolExecutorService integrationToolExecutorService;

    private final ServerToolExecutorService serverToolExecutorService;

    public void pushToConnector(ToolUseLog toolUseLog) {
        Connector connector = connectorRepository.findById(toolUseLog.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolUseLog.getConnectorCode()));

        var payload = ToolUsePayload.from(toolUseLog);
        UUID userId = toolUseLog.getUserId();
        UUID agentId = toolUseLog.getAgentId();

        switch (connector.getType()) {
            case APP -> {
                var app = appRepository.findByIdAndUserIdNotDeleted(UUID.fromString(payload.identity()), userId)
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + payload.identity()));
                centrifugoService.publishMessage("device:" + app.getDeviceId(), "toolUse", payload);
            }
            case INTEGRATION -> {
                var credentials = integrationCredentialsRepository.findByIdAndUserIdNotDeleted(UUID.fromString(payload.identity()), userId)
                        .orElseThrow(() -> new NotFoundStatusException("Integration credentials not found: " + payload.identity()));
                integrationToolExecutorService.execute(credentials, payload, agentId);
            }
            case INTERNAL_SERVICE -> serverToolExecutorService.execute(payload, agentId, userId);
            case LOOPBACK -> log.warn("LOOPBACK connector called, ignoring. connectorCode={}, toolUse={}", payload.connectorCode(), payload.name());
        }
    }

}
